package ai.intentoms.agent;

import ai.intentoms.agent.mandate.Mandate;
import ai.intentoms.agent.mandate.MandateCheck;
import ai.intentoms.agent.mandate.MandateService;
import ai.intentoms.agent.registry.AgentIdentity;
import ai.intentoms.agent.registry.AgentRegistry;
import ai.intentoms.agent.screen.AgentTextSanitizer;
import ai.intentoms.agent.screen.InjectionScreen;
import ai.intentoms.agent.screen.ScreenResult;
import ai.intentoms.finance.model.Money;
import ai.intentoms.order.capture.CaptureRequest;
import ai.intentoms.order.model.Note;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.service.OrderService;
import ai.intentoms.order.validation.steps.AgentProvenanceStep;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.statemachine.HoldService;
import ai.intentoms.workflow.task.HumanTask;
import ai.intentoms.workflow.task.HumanTaskService;
import ai.intentoms.workflow.task.TaskRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Places orders on behalf of agents for both MCP {@code order_create} and UCP {@code POST /ucp/v1/orders}
 * (FR-AGT-01/02, FR-CAP-07, FR-AGT-04/05, FR-FIN-06). Pipeline: per-agent order value limit → mandate verification →
 * injection screen on instruction text and notes (GR-08) → human confirmation when the agent's {@code confirmActions}
 * include {@code order_create} → {@code CaptureRequest} on channel {@code agent} with full provenance → capture →
 * mandate consumed → {@code agent_verification} hold + {@code agent.injection_flagged} when text was flagged.
 * Flagged text is stored as data with {@code injectionRisk}; it is never merged into a prompt.
 */
@Service
public class AgentOrderService {
    public static final String CHANNEL = "agent";
    public static final String QUEUE_RISK_REVIEW = "risk_review";
    public static final String TASK_CONFIRMATION = "agent_confirmation";
    private static final Logger log = LoggerFactory.getLogger(AgentOrderService.class);

    /** What the caller asked for: the agent, its protocol, the order as a capture request subset, the mandate and the tool name. */
    public record PlaceRequest(AgentIdentity agent, String protocol, CaptureRequest order, String mandateId, String buyerRef, String instructionText, String idempotencyKey, String tool, Map<String, Object> rawArgs) {}

    /** Either the placed order or a pending human confirmation. */
    public sealed interface PlaceResult permits Placed, PendingConfirmation {}
    public record Placed(Order order, boolean replayed, MandateCheck mandate, List<ScreenResult> screens, String holdId) implements PlaceResult {}
    public record PendingConfirmation(String taskId, String queue) implements PlaceResult {}

    private final OrderService orders;
    private final MandateService mandates;
    private final InjectionScreen screen;
    private final HoldService holds;
    private final HumanTaskService tasks;
    private final EventStore events;
    private final AgentRegistry registry;
    private final Metrics metrics;

    public AgentOrderService(OrderService orders, MandateService mandates, InjectionScreen screen, HoldService holds, HumanTaskService tasks, EventStore events, AgentRegistry registry, Metrics metrics) {
        this.orders = orders;
        this.mandates = mandates;
        this.screen = screen;
        this.holds = holds;
        this.tasks = tasks;
        this.events = events;
        this.registry = registry;
        this.metrics = metrics;
    }

    public PlaceResult place(PlaceRequest req) {
        AgentIdentity agent = req.agent();
        CaptureRequest in = req.order();
        if (in == null || in.lines().isEmpty()) throw new ValidationException("order.lines must contain at least one line");
        Money total = in.merchandiseTotal().add(in.chargeTotal());
        if (in.channelOrderId() != null && !in.channelOrderId().isBlank()) {
            Optional<Order> existing = orders.byChannelRef(CHANNEL, in.channelOrderId());
            if (existing.isPresent()) {
                Order prior = existing.get();
                if (prior.provenance() == null || !agent.id().equals(prior.provenance().agentId()))
                    throw new ConflictException("Order reference " + in.channelOrderId() + " already exists on the agent channel", Map.of("channelOrderId", in.channelOrderId()));
                metrics.count("oms.agent.order", "outcome", "replayed", "protocol", req.protocol());
                return new Placed(prior, true, MandateCheck.ok(prior.provenance().mandateId() == null ? null : mandates.find(prior.provenance().mandateId()).orElse(null)), List.of(), null);
            }
        }
        if (agent.limits().exceedsOrderValue(total)) {
            throw new AgentToolException(AgentToolException.LIMIT_EXCEEDED, "limit_exceeded", "Order total " + total + " exceeds the agent's maximum order value " + agent.limits().maxOrderValue(),
                Map.of("total", CustomerSafeOrderView.money(total), "maxOrderValue", CustomerSafeOrderView.money(agent.limits().maxOrderValue())));
        }
        MandateCheck check = mandates.verify(req.mandateId(), agent.id(), total, req.buyerRef());
        if (!check.valid()) {
            throw new AgentToolException(AgentToolException.MANDATE_INVALID, "mandate_invalid", "Mandate " + req.mandateId() + " does not authorise this order: " + String.join(", ", check.reasons()),
                Map.of("mandateId", String.valueOf(req.mandateId()), "reasons", check.reasons()));
        }
        Mandate mandate = check.mandate();
        String instruction = AgentTextSanitizer.sanitize(req.instructionText() != null && !req.instructionText().isBlank() ? req.instructionText() : mandate.instructionText());
        if (instruction == null || instruction.isBlank()) throw new ValidationException("instructionText is required as evidence on agent orders (FR-CAP-07)");

        List<ScreenResult> screens = new ArrayList<>();
        // NF-07: the buyer's own name, address lines and contact details are removed from the screened text too.
        java.util.List<String> names = new ArrayList<>(), lines1 = new ArrayList<>(), emails = new ArrayList<>(), phones = new ArrayList<>();
        if (in.customer() != null) { names.add(in.customer().name()); emails.add(in.customer().email()); phones.add(in.customer().phone()); }
        if (in.addresses() != null) for (CaptureRequest.AddressInput a : in.addresses()) { if (a == null) continue; names.add(a.name()); lines1.add(a.line1()); lines1.add(a.line2()); phones.add(a.phone()); }
        ai.intentoms.platform.pii.KnownPii known = new ai.intentoms.platform.pii.KnownPii(names, lines1, emails, phones);
        screens.add(screen.screen("agent_instruction", instruction, "agent_text", "agent_instruction", known));
        List<CaptureRequest.NoteInput> notes = new ArrayList<>();
        for (CaptureRequest.NoteInput n : in.notes()) {
            String text = AgentTextSanitizer.sanitize(n.text());
            if (text == null || text.isBlank()) continue;
            screens.add(screen.screen("agent_note", text, "agent_text", "agent_note", known));
            notes.add(new CaptureRequest.NoteInput("agent", agent.id(), text, Boolean.TRUE.equals(n.customerVisible())));
        }
        boolean flagged = screens.stream().anyMatch(ScreenResult::flagged);

        if (agent.limits().requiresConfirmation(req.tool())) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tool", req.tool());
            payload.put("agentId", agent.id());
            payload.put("protocol", req.protocol());
            payload.put("mandateId", mandate.id());
            payload.put("total", CustomerSafeOrderView.money(total));
            payload.put("instructionText", instruction);
            payload.put("injectionFlagged", flagged);
            payload.put("arguments", req.rawArgs() == null ? Map.of() : req.rawArgs());
            HumanTask task = tasks.create(new TaskRequest(TASK_CONFIRMATION, QUEUE_RISK_REVIEW, "agent", agent.id(), "Confirm " + req.tool() + " by agent " + agent.id() + " (" + total + ")",
                payload, Duration.ofHours(4), java.util.Set.of(), flagged ? 10 : 5, null));
            events.append(AgentRegistry.AGGREGATE, agent.id(), new NewEvent("agent.confirmation_requested", Map.of("agentId", agent.id(), "tool", req.tool(), "taskId", task.id(), "mandateId", mandate.id()), Actor.agent(agent.id()), null, mandate.id(), Map.of()));
            metrics.count("oms.agent.order", "outcome", "pending_confirmation", "protocol", req.protocol());
            return new PendingConfirmation(task.id(), QUEUE_RISK_REVIEW);
        }

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("injectionScreen", screens.get(0).toMap());
        raw.put("noteScreens", screens.stream().skip(1).map(ScreenResult::toMap).toList());
        raw.put("mandateSignatureValid", mandate.signatureValid());
        raw.put("mandateScope", mandate.scopeLabels());
        raw.put("mandateExpiresAt", mandate.expiresAt().toString());
        raw.put("paymentTokenPresent", mandate.paymentTokenPresent());
        raw.put("tool", req.tool());
        if (req.rawArgs() != null && req.rawArgs().get("buyerAcknowledgement") != null) raw.put("buyerAcknowledgement", req.rawArgs().get("buyerAcknowledgement"));
        CaptureRequest.ProvenanceInput provenance = new CaptureRequest.ProvenanceInput(agent.id(), req.protocol(), mandate.id(), mandate.spendLimit(), instruction, mandate.signature(),
            req.buyerRef() != null ? req.buyerRef() : mandate.buyerRef(), raw);
        List<CaptureRequest.TenderInput> tenders = in.tenders().isEmpty()
            ? List.of(new CaptureRequest.TenderInput("agent_token", mandate.paymentToken() != null ? mandate.paymentToken() : "mandate-" + mandate.id(), total, Map.of("mandateId", mandate.id())))
            : in.tenders().stream().map(t -> t.amount() == null ? new CaptureRequest.TenderInput(t.type() == null ? "agent_token" : t.type(), t.token(), total, t.details() == null ? Map.of("mandateId", mandate.id()) : t.details()) : t).toList();
        String channelOrderId = in.channelOrderId() == null || in.channelOrderId().isBlank() ? "AGT-" + Ids.shortId() : in.channelOrderId();
        String customerRef = in.customerRef() != null ? in.customerRef() : in.customer() != null && in.customer().ref() != null ? in.customer().ref() : req.buyerRef() != null ? req.buyerRef() : mandate.buyerRef();
        CaptureRequest capture = new CaptureRequest(CHANNEL, channelOrderId, in.orderType(), customerRef, in.customer(), in.lines(), in.addresses(), in.charges(), tenders, notes,
            in.intentChoices(), in.shipMethod(), in.placedAt(), in.currency(), in.extensions(), provenance);

        OrderService.CaptureResult result = orders.captureDetailed(capture, req.idempotencyKey());
        Order order = result.order();
        String holdId = null;
        if (!result.replayed()) {
            mandates.consume(mandate.id(), order.id());
            if (flagged) holdId = flagInjection(order, agent, screens);
            registry.recordActivity(agent.id(), req.tool(), "ok", order.id(), 0, Map.of("protocol", req.protocol(), "total", total.amount()));
        }
        metrics.count("oms.agent.order", "outcome", result.replayed() ? "replayed" : "placed", "protocol", req.protocol());
        log.info("Agent {} placed order {} under mandate {} via {} (flagged={})", agent.id(), order.id(), mandate.id(), req.protocol(), flagged);
        return new Placed(order, result.replayed(), check, screens, holdId);
    }

    /** Flagged text stays on the order as data; the order is held for human verification (GR-08, FR-AGT-05). */
    private String flagInjection(Order order, AgentIdentity agent, List<ScreenResult> screens) {
        double maxRisk = screens.stream().mapToDouble(ScreenResult::probability).max().orElse(0);
        Actor actor = Actor.agent(agent.id());
        var hold = holds.place(AgentProvenanceStep.HOLD_TYPE, "order", order.id(), AgentProvenanceStep.HOLD_BLOCKS, "INJECTION_FLAGGED",
            "Agent-supplied text looks like instructions aimed at the system (p=" + maxRisk + "); human verification required", null, actor);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", order.id());
        payload.put("agentId", agent.id());
        payload.put("holdId", hold.id());
        payload.put("screens", screens.stream().map(ScreenResult::toMap).toList());
        orders.update(order.id(), o -> {
            List<Note> notes = new ArrayList<>();
            if (o.notes() != null) for (Note n : o.notes()) notes.add("agent".equals(n.source()) && n.injectionRisk() == null ? new Note(n.source(), n.author(), n.text(), n.at(), n.customerVisible(), maxRisk) : n);
            return o.withNotes(notes);
        }, new NewEvent("agent.injection_flagged", payload, actor, null, order.id(), Map.of("agentId", agent.id())));
        events.append(AgentRegistry.AGGREGATE, agent.id(), new NewEvent("agent.injection_flagged", payload, actor, null, order.id(), Map.of()));
        metrics.count("oms.agent.injection_hold");
        return hold.id();
    }
}
