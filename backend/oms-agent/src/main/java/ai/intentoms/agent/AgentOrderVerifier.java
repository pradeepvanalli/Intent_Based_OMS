package ai.intentoms.agent;

import ai.intentoms.agent.mandate.Mandate;
import ai.intentoms.agent.registry.AgentIdentity;
import ai.intentoms.agent.mandate.MandateService;
import ai.intentoms.agent.registry.AgentRegistry;
import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.runtime.DecisionService;
import ai.intentoms.order.model.AgentProvenance;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.service.OrderService;
import ai.intentoms.order.validation.steps.AgentProvenanceStep;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.NewEvent;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.statemachine.HoldService;
import ai.intentoms.statemachine.model.Hold;
import ai.intentoms.workflow.task.HumanTask;
import ai.intentoms.workflow.task.HumanTaskService;
import ai.intentoms.workflow.task.TaskQuery;
import ai.intentoms.workflow.task.TaskRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Mandate decision hook (PRD section 9 "Agent order within mandate", T3; FR-CAP-07, FR-AGT-05, GR-14). The orchestration
 * workflow calls {@link #verify} after capture: the T3 {@code agent_mandate_match} Noul is a recommendation only, so the
 * record id and value are stored on the provenance ({@code mandateDecisionRecordId}, {@code withinMandate}) and whenever
 * the decision requires a human or answers {@code false} an {@code agent_verification} hold is ensured and an
 * {@code agent_review} task is queued in {@code risk_review}. A person then calls {@link #confirm}, which releases the
 * hold (approved) or keeps it and records the rejection. The agent never approves its own order (GR-14).
 */
@Service
public class AgentOrderVerifier {
    public static final String DECISION = "agent_mandate_match";
    public static final String TASK_REVIEW = "agent_review";
    public static final String QUEUE = AgentOrderService.QUEUE_RISK_REVIEW;
    private static final Logger log = LoggerFactory.getLogger(AgentOrderVerifier.class);

    public record Verification(String orderId, String recordId, Boolean withinMandate, boolean requiresHuman, String action, String holdId, String taskId) {}

    private final OrderService orders;
    private final MandateService mandates;
    private final AgentRegistry registry;
    private final DecisionService decisions;
    private final HoldService holds;
    private final HumanTaskService tasks;
    private final AuthorizationService authz;
    private final Metrics metrics;

    public AgentOrderVerifier(OrderService orders, MandateService mandates, AgentRegistry registry, DecisionService decisions, HoldService holds, HumanTaskService tasks, AuthorizationService authz, Metrics metrics) {
        this.orders = orders;
        this.mandates = mandates;
        this.registry = registry;
        this.decisions = decisions;
        this.holds = holds;
        this.tasks = tasks;
        this.authz = authz;
        this.metrics = metrics;
    }

    public Verification verify(String orderId) {
        Order order = orders.get(orderId);
        AgentProvenance p = order.provenance();
        if (p == null || p.agentId() == null) throw new NotFoundException("Order " + orderId + " has no agent provenance");
        Optional<Mandate> mandate = p.mandateId() == null ? Optional.empty() : mandates.find(p.mandateId());
        int tier = registry.find(p.agentId()).map(AgentIdentity::trustTier).orElse(0);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instruction_text", p.instructionText() == null ? "" : ai.intentoms.platform.pii.FreeTextRedactor.redact(p.instructionText())); // NF-07
        m.put("spend_limit", mandate.map(x -> x.spendLimit().amount()).orElse(p.spendLimit() == null ? null : p.spendLimit().amount()));
        m.put("scope", mandate.map(Mandate::scopeLabels).orElse(List.of()));
        m.put("expires_at", mandate.map(x -> x.expiresAt().toString()).orElse(null));
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("total", order.totals() == null ? order.merchandiseTotal().amount() : order.totals().grandTotal().amount());
        o.put("items_summary", order.lines().stream().map(l -> l.qty() + " x " + (l.description() == null ? l.sku() : l.description())).collect(Collectors.joining(", ")));
        o.put("ship_to_country", order.shipTo().map(a -> a.country()).orElse(null));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("mandate", m);
        input.put("order", o);
        input.put("agent", Map.of("trust_tier", tier));

        DecisionResult r = decisions.decide(DECISION, input, DecisionOptions.live("order", orderId, actor()));
        Boolean within = r.value() instanceof Boolean b ? b : r.value() instanceof Number n ? n.doubleValue() >= 0.5 : null;
        boolean needsHuman = r.requiresHuman() || Boolean.FALSE.equals(within) || !r.acts();
        String holdId = null;
        String taskId = null;
        if (needsHuman) {
            Hold hold = ensureHold(orderId, r);
            holdId = hold.id();
            taskId = ensureTask(order, r, within);
        }
        final Boolean storedWithin = needsHuman ? (Boolean.FALSE.equals(within) ? Boolean.FALSE : null) : within;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId);
        payload.put("agentId", p.agentId());
        payload.put("mandateId", p.mandateId());
        payload.put("recordId", r.recordId());
        payload.put("value", r.value());
        payload.put("action", r.action());
        payload.put("requiresHuman", r.requiresHuman());
        payload.put("holdId", holdId);
        payload.put("taskId", taskId);
        orders.update(orderId, cur -> withProvenance(cur, new AgentProvenance(p.agentId(), p.protocol(), p.mandateId(), p.spendLimit(), p.instructionText(), p.signature(), p.buyerRef(),
            storedWithin, r.recordId(), p.raw())), new NewEvent("agent.order_verified", payload, actor(), null, orderId, Map.of("agentId", p.agentId())));
        metrics.count("oms.agent.mandate_match", "action", String.valueOf(r.action()), "human", String.valueOf(needsHuman));
        log.info("agent_mandate_match for order {}: value={} action={} human={} record={}", orderId, r.value(), r.action(), needsHuman, r.recordId());
        return new Verification(orderId, r.recordId(), storedWithin, needsHuman, r.action(), holdId, taskId);
    }

    /** Human decision on an agent order: approving releases the verification hold, rejecting keeps it and records why. */
    public Verification confirm(String orderId, boolean approved, String reason) {
        if (reason == null || reason.isBlank()) throw new ValidationException("reason is required");
        Order order = orders.get(orderId);
        AgentProvenance p = order.provenance();
        if (p == null || p.agentId() == null) throw new NotFoundException("Order " + orderId + " has no agent provenance");
        Actor actor = actor();
        List<Hold> active = holds.active("order", orderId).stream().filter(h -> AgentProvenanceStep.HOLD_TYPE.equals(h.type())).toList();
        String holdId = active.isEmpty() ? null : active.get(0).id();
        if (approved) for (Hold h : active) holds.release(h.id(), "approved: " + reason, actor);
        for (HumanTask t : tasks.list(new TaskQuery(QUEUE, null, null, "order", orderId, null, 0, 50)).items()) {
            if (TASK_REVIEW.equals(t.type()) && t.isOpen()) tasks.complete(t.id(), approved ? "approved" : "rejected", reason, Map.of("by", actor.ref()));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId);
        payload.put("agentId", p.agentId());
        payload.put("approved", approved);
        payload.put("reason", reason);
        payload.put("releasedHolds", approved ? active.stream().map(Hold::id).toList() : List.of());
        orders.update(orderId, cur -> withProvenance(cur, new AgentProvenance(p.agentId(), p.protocol(), p.mandateId(), p.spendLimit(), p.instructionText(), p.signature(), p.buyerRef(),
            approved, p.mandateDecisionRecordId(), p.raw())), new NewEvent(approved ? "agent.order_confirmed" : "agent.order_rejected", payload, actor, null, orderId, Map.of("agentId", p.agentId())));
        metrics.count("oms.agent.confirm", "approved", String.valueOf(approved));
        return new Verification(orderId, p.mandateDecisionRecordId(), approved, false, approved ? "approved" : "rejected", approved ? null : holdId, null);
    }

    private Hold ensureHold(String orderId, DecisionResult r) {
        for (Hold h : holds.active("order", orderId)) if (AgentProvenanceStep.HOLD_TYPE.equals(h.type())) return h;
        return holds.place(AgentProvenanceStep.HOLD_TYPE, "order", orderId, AgentProvenanceStep.HOLD_BLOCKS, "MANDATE_REVIEW",
            "agent_mandate_match " + r.action() + " (value=" + r.value() + ", confidence=" + r.confidence() + "); human verification required", null, Actor.decision(r.recordId()));
    }

    private String ensureTask(Order order, DecisionResult r, Boolean within) {
        for (HumanTask t : tasks.list(new TaskQuery(QUEUE, null, null, "order", order.id(), null, 0, 50)).items()) if (TASK_REVIEW.equals(t.type()) && t.isOpen()) return t.id();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", order.id());
        payload.put("agentId", order.provenance().agentId());
        payload.put("mandateId", order.provenance().mandateId());
        payload.put("decisionRecordId", r.recordId());
        payload.put("recommendation", within);
        payload.put("confidence", r.confidence());
        payload.put("reasonCodes", r.reasonCodes());
        payload.put("instructionText", order.provenance().instructionText());
        payload.put("itemsSummary", order.lines().stream().map(l -> l.qty() + " x " + l.sku()).toList());
        HumanTask t = tasks.create(new TaskRequest(TASK_REVIEW, QUEUE, "order", order.id(), "Verify agent order " + order.id() + " against mandate", payload, Duration.ofHours(4), Set.of(), 5, null));
        return t.id();
    }

    private static Order withProvenance(Order o, AgentProvenance p) {
        return new Order(o.id(), o.channel(), o.channelOrderId(), o.orderType(), o.customerRef(), o.customer(), o.lines(), o.addresses(), o.charges(), o.tenders(), o.notes(), o.fulfilmentGroups(),
            o.intent(), p, o.totals(), o.margin(), o.stateModelId(), o.stateModelVersion(), o.status(), o.shipMethod(), o.currency(), o.extensions(), o.placedAt(), o.capturedAt(), o.updatedAt(), o.version());
    }

    private Actor actor() { return authz.currentPrincipal().map(x -> Actor.parse(x.actorRef())).orElse(Actor.system("agent-verifier")); }

}
