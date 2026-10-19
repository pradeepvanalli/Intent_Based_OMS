package ai.intentoms.returns.service;

import ai.intentoms.decision.model.Answer;
import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.model.QuestionDef;
import ai.intentoms.decision.runtime.DecisionService;
import ai.intentoms.finance.model.Money;
import ai.intentoms.finance.payment.PaymentService;
import ai.intentoms.finance.payment.Refund;
import ai.intentoms.finance.payment.RefundStatus;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.model.OrderLine;
import ai.intentoms.order.store.OrderRepository;
import ai.intentoms.platform.audit.AuditLog;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.error.GuardrailViolationException;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.jdbc.Page;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.pii.FreeTextRedactor;
import ai.intentoms.platform.pii.Tokenizer;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.time.TimeSource;
import ai.intentoms.returns.config.ReturnsConfigKeys;
import ai.intentoms.returns.model.*;
import ai.intentoms.returns.store.ReturnRepository;
import ai.intentoms.statemachine.StateMachine;
import ai.intentoms.statemachine.model.EntityStatus;
import ai.intentoms.statemachine.model.QuantityBucket;
import ai.intentoms.statemachine.model.TransitionRequest;
import ai.intentoms.statemachine.model.TransitionResult;
import ai.intentoms.workflow.task.HumanTask;
import ai.intentoms.workflow.task.HumanTaskService;
import ai.intentoms.workflow.task.TaskRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Returns as margin recovery (PRD §7.10). One RMA goes through: {@link #create} (FR-RET-01, any channel) →
 * {@link #decideEligibility} (FR-RET-02/03: L0 policy + {@code return_risk} Score with reason-code Nouls → refund timing;
 * FR-RET-05 keep-it) → {@link #recordScan} / {@link #receive} / {@link #inspect} → {@link #refund} (FR-RET-04 via finance,
 * original tender split, restocking fee, store credit) → {@link #disposition} (FR-RET-06).
 *
 * <p>Guardrail placement (PRD §8): refund timing is T2 — the system acts only when the decision is in the ACT band,
 * otherwise the conservative {@code on_inspection} timing applies or a {@code return_review} task is opened; denying a
 * return is T3 — a denial is always a <em>recommendation</em> that a person confirms through {@link #applyDenial};
 * refunds above the tenant threshold or refused by the GR-05 {@code SpendGuard} wait in {@code refund_approvals} for
 * {@link #approveRefund}. Every human decision carries a reason and is audited (GR-13).</p>
 *
 * <p>State: entity {@code return/<rmaId>} on model {@code return.standard}; {@code return_rma.status} mirrors its
 * dominant state. Ledger: aggregate {@code return}, events in {@link ReturnEvents}, correlationId = orderId. Money
 * moves outside the service's own transactions (finance commits each refund leg itself), so a failed bookkeeping step
 * can never roll back a PSP refund.</p>
 */
@Service
public class ReturnService {
    private static final Logger log = LoggerFactory.getLogger(ReturnService.class);
    static final String ENTITY = "return";
    static final String MODEL = "return.standard";
    static final String DECISION_RISK = "return_risk";
    static final String DECISION_REASON = "return_reason";
    static final String DECISION_DISPOSITION = "return_disposition";
    static final String MAIN_RISK_QUESTION = "abuse_risk";
    static final String REASON_OTHER = "other";
    private static final Set<String> DELIVERED_STATES = Set.of("delivered", "picked_up");
    private static final Duration REVIEW_SLA = Duration.ofHours(24);
    private static final Duration REFUND_APPROVAL_SLA = Duration.ofHours(8);

    private final ReturnRepository returns;
    private final OrderRepository orders;
    private final StateMachine machine;
    private final DecisionService decisions;
    private final PaymentService payments;
    private final HumanTaskService tasks;
    private final EventStore events;
    private final TenantConfigStore config;
    private final TimeSource time;
    private final TransactionTemplate tx;
    private final CustomerReturnHistory history;
    private final ReturnWindowResolver windows;
    private final StoreCreditIssuer storeCredit;
    private final Tokenizer tokenizer;
    private final AuditLog audit;
    private final Metrics metrics;
    private final AuthorizationService authz;

    public ReturnService(ReturnRepository returns, OrderRepository orders, StateMachine machine, DecisionService decisions, PaymentService payments,
                         HumanTaskService tasks, EventStore events, TenantConfigStore config, TimeSource time, TransactionTemplate tx, CustomerReturnHistory history,
                         ReturnWindowResolver windows, StoreCreditIssuer storeCredit, Tokenizer tokenizer, AuditLog audit, Metrics metrics, AuthorizationService authz) {
        this.returns = returns;
        this.orders = orders;
        this.machine = machine;
        this.decisions = decisions;
        this.payments = payments;
        this.tasks = tasks;
        this.events = events;
        this.config = config;
        this.time = time;
        this.tx = tx;
        this.history = history;
        this.windows = windows;
        this.storeCredit = storeCredit;
        this.tokenizer = tokenizer;
        this.audit = audit;
        this.metrics = metrics;
        this.authz = authz;
    }

    // ------------------------------------------------------------------------------------------------ FR-RET-01 create

    /**
     * Opens an RMA from any channel. Validates the order and that every line has returnable quantity (units in
     * {@code delivered} / {@code picked_up}, plus {@code shipped} when {@code returns.allow_in_transit}, minus units already
     * on other RMAs), normalises the reason through the T0 {@code return_reason} decision, initialises the state machine
     * and appends {@code return.requested}.
     */
    public ReturnRma create(ReturnRequest req, Actor actor) {
        Actor who = actorOrCurrent(actor);
        if (req == null || req.orderId() == null || req.orderId().isBlank()) throw new ValidationException("orderId is required");
        if (req.lines().isEmpty()) throw new ValidationException("at least one line is required");
        String channel = ReturnChannel.normalise(req.channel());
        for (ReturnLineRequest l : req.lines()) {
            if (l.lineId() == null || l.lineId().isBlank()) throw new ValidationException("lineId is required on every line");
            if (l.qty() <= 0) throw new ValidationException("qty must be positive on line " + l.lineId());
        }
        ReturnRma created = tx.execute(s -> createTx(req, channel, who));
        metrics.count("oms.returns.created", "channel", channel);
        return created;
    }

    /**
     * Buy-online-return-in-store (FR-RET-01): resolves the order from the receipt, opens the RMA on channel {@code store},
     * decides eligibility and, when approved, records the hand-over at the store node in one go. A denial recommendation
     * leaves the RMA in {@code requested} for the risk queue; the associate sees that in the returned status.
     */
    public ReturnRma createInStore(InStoreReturnRequest req, Actor actor) {
        if (req == null) throw new ValidationException("request body is required");
        if (req.storeNodeId() == null || req.storeNodeId().isBlank()) throw new ValidationException("storeNodeId is required");
        String orderId = req.orderId();
        if (orderId == null || orderId.isBlank()) {
            if (req.channelOrderId() == null || req.channelOrderId().isBlank()) throw new ValidationException("orderId or channelOrderId is required");
            String channel = req.channel() == null || req.channel().isBlank() ? ReturnChannel.WEB : req.channel();
            orderId = orders.byChannelRef(channel, req.channelOrderId())
                .orElseThrow(() -> new NotFoundException("No order " + req.channelOrderId() + " on channel " + channel)).id();
        }
        boolean receipt = req.receiptPresent() == null || req.receiptPresent();
        ReturnRma rma = create(new ReturnRequest(orderId, req.lines(), ReturnChannel.STORE, null, null, receipt, req.storeNodeId(), null), actor);
        rma = decideEligibility(rma.rmaId(), actor);
        if (ReturnStatus.AWAITING_RECEIPT.equals(rma.status()) || ReturnStatus.APPROVED.equals(rma.status()) || ReturnStatus.REFUNDED.equals(rma.status())) {
            rma = receive(rma.rmaId(), req.storeNodeId(), actor);
        }
        return rma;
    }

    private ReturnRma createTx(ReturnRequest req, String channel, Actor who) {
        Order order = orders.get(req.orderId());
        Map<String, Integer> alreadyReturned = returns.returnedQtyByLine(order.id());
        boolean allowInTransit = config.getBoolean(ReturnsConfigKeys.ALLOW_IN_TRANSIT, false);
        List<String> taxonomy = taxonomy();
        List<ReturnLine> lines = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ReturnLineRequest lr : req.lines()) {
            if (!seen.add(lr.lineId())) throw new ValidationException("Line " + lr.lineId() + " appears twice");
            OrderLine ol = order.line(lr.lineId()).orElseThrow(() -> new ValidationException("Line " + lr.lineId() + " is not on order " + order.id(), Map.of("lineId", lr.lineId())));
            int returnable = returnableQty(order.id(), ol, allowInTransit) - alreadyReturned.getOrDefault(lr.lineId(), 0);
            if (lr.qty() > returnable) {
                throw new ValidationException("Line " + lr.lineId() + " has " + Math.max(0, returnable) + " returnable unit(s), " + lr.qty() + " requested",
                    Map.of("lineId", lr.lineId(), "returnable", Math.max(0, returnable), "requested", lr.qty()));
            }
            String provided = lr.reasonCode() == null ? null : lr.reasonCode().trim().toLowerCase(Locale.ROOT);
            lines.add(new ReturnLine(lr.lineId(), ol.sku(), ol.description(), lr.qty(), ol.unitPrice(), RefundCalculator.taxShare(ol, lr.qty()),
                provided != null && taxonomy.contains(provided) ? provided : null, lr.customerReasonText(), null, null, ol.attributes()));
        }
        String rmaId = Ids.prefixed("rma");
        ReasonOutcome reason = normaliseReason(rmaId, req, lines, taxonomy, who);
        List<ReturnLine> withReasons = lines.stream().map(l -> l.reasonCode() != null ? l : new ReturnLine(l.lineId(), l.sku(), l.description(), l.qty(), l.unitPrice(), l.tax(),
            reason.code, l.customerReasonText(), null, null, l.attributes())).toList();
        String emailToken = order.customer() != null && order.customer().email() != null && !order.customer().email().isBlank() ? tokenizer.email(order.customer().email()) : null;
        Instant now = time.now();

        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("orderId", order.id());
        attrs.put("channel", channel);
        attrs.put("lineCount", withReasons.size());
        if (order.customerRef() != null) attrs.put("customerRef", order.customerRef());
        if (req.agentId() != null) attrs.put("agentId", req.agentId());
        machine.initialize(ENTITY, rmaId, MODEL, null, 1, attrs, who);

        ReturnRma rma = new ReturnRma(rmaId, order.id(), channel, order.customerRef(), emailToken, ReturnStatus.REQUESTED, reason.code, joinedText(req, lines), null, null,
            null, null, List.of(), null, null, reason.recordId, null, null, null, KeepIt.notEvaluated(), null, req.receiptPresent(), req.storeNodeId(), req.agentId(), req.mandateId(),
            null, null, null, null, null, null, null, null, who.ref(), now, now, withReasons);
        returns.insert(rma);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", order.id());
        payload.put("channel", channel);
        payload.put("customerRef", order.customerRef());
        payload.put("reasonCode", reason.code);
        payload.put("reasonDecisionRecordId", reason.recordId);
        payload.put("receiptPresent", req.receiptPresent());
        payload.put("storeNodeId", req.storeNodeId());
        payload.put("agentId", req.agentId());
        payload.put("mandateId", req.mandateId());
        payload.put("lines", withReasons.stream().map(l -> Map.of("lineId", l.lineId(), "sku", l.sku(), "qty", l.qty(), "unitPrice", l.unitPrice().amount(), "reasonCode", nz(l.reasonCode()))).toList());
        append(rma, ReturnEvents.REQUESTED, payload, who, reason.recordId);
        return rma;
    }

    private int returnableQty(String orderId, OrderLine line, boolean allowInTransit) {
        EntityStatus st;
        try { st = machine.status("order_line", orderId + ":" + line.lineId()); }
        catch (NotFoundException e) { throw new ValidationException("Line " + line.lineId() + " on order " + orderId + " has no fulfilment state yet", Map.of("lineId", line.lineId())); }
        int qty = 0;
        for (QuantityBucket b : st.quantities()) if (DELIVERED_STATES.contains(b.state()) || (allowInTransit && "shipped".equals(b.state()))) qty += b.qty();
        return qty;
    }

    /** Days since the earliest delivery among the returned lines (conservative for the window check); 0 when only in transit. */
    private Integer daysSinceDelivery(String orderId, List<ReturnLine> lines) {
        Instant earliest = null;
        for (ReturnLine l : lines) {
            EntityStatus st;
            try { st = machine.status("order_line", orderId + ":" + l.lineId()); } catch (NotFoundException e) { continue; }
            for (QuantityBucket b : st.quantities()) {
                if (DELIVERED_STATES.contains(b.state()) && b.since() != null && (earliest == null || b.since().isBefore(earliest))) earliest = b.since();
            }
        }
        if (earliest == null) return 0;
        return (int) Math.max(0, ChronoUnit.DAYS.between(earliest, time.now()));
    }

    private record ReasonOutcome(String code, String recordId) {}

    /** NF-07: the order's own names, addresses and contact details, to redact from a return's free text. */
    private ai.intentoms.platform.pii.KnownPii orderPii(String orderId) {
        try { return orderId == null ? ai.intentoms.platform.pii.KnownPii.NONE : ai.intentoms.order.model.OrderPii.known(orders.get(orderId)); }
        catch (RuntimeException e) { return ai.intentoms.platform.pii.KnownPii.NONE; }
    }

    /** T0 reason normalisation: acts at confidence ≥ 0.5, the escape option ({@code unclear}) and anything off-taxonomy become {@code other}. */
    private ReasonOutcome normaliseReason(String rmaId, ReturnRequest req, List<ReturnLine> lines, List<String> taxonomy, Actor who) {
        String providedCode = lines.stream().map(ReturnLine::reasonCode).filter(Objects::nonNull).findFirst()
            .orElseGet(() -> req.lines().stream().map(ReturnLineRequest::reasonCode).filter(c -> c != null && !c.isBlank()).findFirst().orElse(null));
        String text = joinedText(req, lines);
        boolean allCoded = lines.stream().allMatch(l -> l.reasonCode() != null);
        if (allCoded && (text == null || text.isBlank())) return new ReasonOutcome(providedCode, null);
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("customer_reason_text", text == null ? "" : FreeTextRedactor.redact(text, orderPii(req.orderId()))); // NF-07: free text, no contact details, names or addresses in the decision log
        ret.put("reason_code", providedCode == null ? "" : providedCode);
        ret.put("item_condition", "unknown");
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("category", lines.isEmpty() ? "" : nz(lines.get(0).attributes().get("category")));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("return", ret);
        input.put("item", item);
        DecisionResult r = decisions.decide(DECISION_REASON, input, DecisionOptions.live(ENTITY, rmaId, who));
        String value = r.value() == null ? null : r.value().toString();
        String code = value != null && taxonomy.contains(value) && !"unclear".equals(value) ? value : (providedCode != null && taxonomy.contains(providedCode) ? providedCode : REASON_OTHER);
        return new ReasonOutcome(code, r.recordId());
    }

    private static String joinedText(ReturnRequest req, List<ReturnLine> lines) {
        List<String> parts = new ArrayList<>();
        if (req.customerReasonText() != null && !req.customerReasonText().isBlank()) parts.add(req.customerReasonText().trim());
        for (ReturnLine l : lines) if (l.customerReasonText() != null && !l.customerReasonText().isBlank()) parts.add(l.customerReasonText().trim());
        return parts.isEmpty() ? null : String.join(" | ", parts);
    }

    private List<String> taxonomy() {
        List<Object> configured = config.getList(ReturnsConfigKeys.REASON_TAXONOMY);
        if (configured.isEmpty()) return ReturnsConfigKeys.DEFAULT_REASON_TAXONOMY;
        List<String> out = new ArrayList<>();
        configured.forEach(o -> out.add(String.valueOf(o)));
        if (!out.contains(REASON_OTHER)) out.add(REASON_OTHER);
        return out;
    }

    // --------------------------------------------------------------------------------------- FR-RET-02/03 eligibility

    private record DecideOutcome(ReturnRma rma, boolean refundNow) {}

    /**
     * L0 policy (window per order type, final sale, digital goods) then the {@code return_risk} decision. Denials are T3
     * recommendations: a {@code return_denial} task is opened and the RMA stays {@code requested} until
     * {@link #applyDenial}. Otherwise the score maps to a refund timing (T2: only when the decision acts; else
     * {@code on_inspection}, or {@code escalate} with a {@code return_review} task when the runtime escalated), the
     * return is approved (label issued → {@code awaiting_receipt}) and, for {@code instant} or a keep-it offer, refunded.
     */
    public ReturnRma decideEligibility(String rmaId, Actor actor) {
        Actor who = actorOrCurrent(actor);
        DecideOutcome out = tx.execute(s -> decideTx(rmaId, who));
        if (out.refundNow()) return refund(rmaId, who);
        return out.rma();
    }

    private DecideOutcome decideTx(String rmaId, Actor who) {
        ReturnRma rma = returns.lock(rmaId);
        if (!ReturnStatus.REQUESTED.equals(rma.status())) throw new ConflictException("Return " + rmaId + " is " + rma.status() + "; eligibility is decided in requested only");
        if (rma.reviewTaskId() != null) throw new ConflictException("Return " + rmaId + " is waiting on human task " + rma.reviewTaskId() + " (" + rma.reviewTaskType() + ")");
        Order order = orders.get(rma.orderId());
        int windowDays = windows.windowDays(order.orderType());
        Integer days = daysSinceDelivery(order.id(), rma.lines());
        Eligibility policy = ReturnPolicy.evaluate(rma.lines(), windowDays, days);
        Map<String, Object> customerHistory = history.build(rma.customerRef(), rma.customerEmailToken(), rmaId);
        Map<String, Object> input = riskInput(rma, order, days, windowDays, policy.finalSale(), customerHistory);
        DecisionResult risk = decisions.decide(DECISION_RISK, input, DecisionOptions.live(ENTITY, rmaId, who));
        Instant now = time.now();

        boolean denied = !policy.eligible() || ReturnPolicy.isDenial(risk.value());
        if (denied) {
            LinkedHashSet<String> reasons = new LinkedHashSet<>(policy.reasons());
            if (ReturnPolicy.isDenial(risk.value())) reasons.add(ReturnPolicy.denialReason(risk.value()));
            Eligibility elig = new Eligibility(false, new ArrayList<>(reasons), windowDays, days, policy.finalSale());
            List<ReasonCode> codes = new ArrayList<>();
            reasons.forEach(r -> codes.add(new ReasonCode(r, 1.0)));
            Map<String, Object> taskPayload = new LinkedHashMap<>();
            taskPayload.put("rmaId", rmaId);
            taskPayload.put("orderId", rma.orderId());
            taskPayload.put("customerRef", rma.customerRef());
            taskPayload.put("recommendation", "reject");
            taskPayload.put("reasons", new ArrayList<>(reasons));
            taskPayload.put("decisionRecordId", risk.recordId());
            taskPayload.put("tier", "T3");
            HumanTask task = tasks.create(new TaskRequest(ReturnTasks.TYPE_RETURN_DENIAL, ReturnTasks.QUEUE_RISK_REVIEW, ENTITY, rmaId,
                "Deny return " + rmaId + " (" + String.join(", ", reasons) + ")", taskPayload, REVIEW_SLA, Set.of("returns_risk"), 5, null));
            rma = rma.patch().eligibility(elig).risk(null, null, codes, risk.recordId()).reviewTask(task.id(), ReturnTasks.TYPE_RETURN_DENIAL).build();
            returns.update(rma, now);
            Map<String, Object> payload = eligibilityPayload(elig, null, null, null, codes, risk);
            payload.put("recommendation", "reject");
            payload.put("taskId", task.id());
            append(rma, ReturnEvents.ELIGIBILITY_DECIDED, payload, who, risk.recordId());
            metrics.count("oms.returns.eligibility", "outcome", "denial_recommended");
            return new DecideOutcome(rma, false);
        }

        Double score = scoreOf(risk);
        List<ReasonCode> codes = reasonCodes(risk);
        String level = score == null ? null : RefundTimingMapper.level(score);
        String timing = score == null ? (risk.requiresHuman() ? RefundTiming.ESCALATE : RefundTiming.ON_INSPECTION) : RefundTimingMapper.map(score, risk.acts(), risk.requiresHuman());

        if (RefundTiming.ESCALATE.equals(timing)) {
            Map<String, Object> taskPayload = new LinkedHashMap<>();
            taskPayload.put("rmaId", rmaId);
            taskPayload.put("orderId", rma.orderId());
            taskPayload.put("customerRef", rma.customerRef());
            taskPayload.put("riskScore", score);
            taskPayload.put("riskLevel", level);
            taskPayload.put("reasonCodes", codes.stream().map(c -> Map.of("code", c.code(), "probability", c.probability())).toList());
            taskPayload.put("recommendation", score == null ? RefundTiming.ON_INSPECTION : RefundTimingMapper.byScore(score));
            taskPayload.put("band", risk.band() == null ? null : risk.band().name());
            taskPayload.put("decisionRecordId", risk.recordId());
            taskPayload.put("escalationId", risk.escalationId());
            taskPayload.put("options", new ArrayList<>(RefundTiming.ACTIONABLE));
            HumanTask task = tasks.create(new TaskRequest(ReturnTasks.TYPE_RETURN_REVIEW, ReturnTasks.QUEUE_RISK_REVIEW, ENTITY, rmaId,
                "Review refund timing for return " + rmaId, taskPayload, REVIEW_SLA, Set.of("returns_risk"), 3, null));
            rma = rma.patch().eligibility(policy).risk(score, level, codes, risk.recordId()).refundTiming(RefundTiming.ESCALATE).refundTimingDecisionRecordId(risk.recordId())
                .reviewTask(task.id(), ReturnTasks.TYPE_RETURN_REVIEW).build();
            returns.update(rma, now);
            Map<String, Object> payload = eligibilityPayload(policy, RefundTiming.ESCALATE, score, level, codes, risk);
            payload.put("taskId", task.id());
            append(rma, ReturnEvents.ELIGIBILITY_DECIDED, payload, who, risk.recordId());
            append(rma, ReturnEvents.REVIEW_REQUESTED, Map.of("taskId", task.id(), "type", ReturnTasks.TYPE_RETURN_REVIEW, "queue", ReturnTasks.QUEUE_RISK_REVIEW), who, risk.recordId());
            metrics.count("oms.returns.eligibility", "outcome", "escalated");
            return new DecideOutcome(rma, false);
        }

        KeepIt keepIt = ReturnChannel.STORE.equals(rma.channel()) ? new KeepIt(false, "in_store", null, null) : evaluateKeepIt(rma.lines(), score);
        rma = rma.patch().eligibility(policy).risk(score, level, codes, risk.recordId()).refundTiming(timing).refundTimingDecisionRecordId(risk.recordId()).keepIt(keepIt).build();
        ReturnRma approved = approve(rma, timing, keepIt, who, risk.recordId(), null);
        Map<String, Object> payload = eligibilityPayload(policy, timing, score, level, codes, risk);
        payload.put("keepItOffered", keepIt.offered());
        append(approved, ReturnEvents.ELIGIBILITY_DECIDED, payload, who, risk.recordId());
        if (keepIt.offered()) {
            Map<String, Object> kp = new LinkedHashMap<>();
            kp.put("returnCostUsd", keepIt.returnCostUsd());
            kp.put("recoveryValueUsd", keepIt.recoveryValueUsd());
            kp.put("reason", keepIt.reason());
            append(approved, ReturnEvents.KEEP_IT_OFFERED, kp, who, risk.recordId());
            metrics.count("oms.returns.keep_it_offered");
        }
        metrics.count("oms.returns.eligibility", "outcome", "approved", "timing", timing);
        return new DecideOutcome(approved, RefundTiming.INSTANT.equals(timing) || keepIt.offered());
    }

    /**
     * Fires {@code decision.return_eligibility} (value approve) and, unless the customer keeps the item, {@code event.label_issued}.
     * {@code approvalPresent} marks a human approval (the model's guard `approval.present` branch).
     */
    private ReturnRma approve(ReturnRma rma, String timing, KeepIt keepIt, Actor who, String causationId, String humanReason) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("decision", Map.of("value", "approve"));
        vars.put("approval", humanReason == null ? Map.of("present", false) : Map.of("present", true, "reason", humanReason));
        TransitionResult tr = fire(rma, "decision.return_eligibility", vars, who, causationId);
        if (!tr.applied()) throw new ConflictException("Return " + rma.rmaId() + " could not be approved: " + tr.blockedBy(), Map.of("blockedBy", tr.blockedBy()));
        if (keepIt == null || !keepIt.offered()) {
            TransitionResult label = fire(rma, "event.label_issued", Map.of(), who, causationId);
            if (!label.applied()) log.warn("Return {} approved but label transition blocked: {}", rma.rmaId(), label.blockedBy());
        }
        ReturnRma updated = rma.patch().status(machine.status(ENTITY, rma.rmaId()).state()).refundTiming(timing).reviewTask(null, null).build();
        return returns.update(updated, time.now());
    }

    private Map<String, Object> riskInput(ReturnRma rma, Order order, Integer days, int windowDays, boolean finalSale, Map<String, Object> customerHistory) {
        Money value = Money.zero(order.currency() == null ? "USD" : order.currency());
        for (ReturnLine l : rma.lines()) value = value.add(l.extended());
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("reason", nz(rma.reasonCode()));
        ret.put("days_since_delivery", days == null ? 0 : days);
        ret.put("receipt", rma.receiptPresent());
        ret.put("item_condition", rma.condition() == null ? "unknown" : rma.condition());
        ret.put("value_usd", value.amount().doubleValue());
        ret.put("line_count", rma.lines().size());
        Map<String, Object> ord = new LinkedHashMap<>();
        ord.put("id", order.id());
        ord.put("channel", order.channel());
        ord.put("order_type", order.orderType());
        ord.put("final_sale", finalSale);
        ord.put("return_channel", rma.channel());
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("history", customerHistory);
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("window_days", windowDays);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("return", ret);
        input.put("order", ord);
        input.put("customer", customer);
        input.put("policy", policy);
        return input;
    }

    /** The abuse-risk Score (0–4) from the main answer, or null when the runtime fell back to the default. */
    static Double scoreOf(DecisionResult risk) {
        Answer main = risk.answers().get(MAIN_RISK_QUESTION);
        if (main != null && !Double.isNaN(main.asDouble())) return main.asDouble();
        if (risk.value() instanceof Number n) return n.doubleValue();
        return null;
    }

    /**
     * Reason codes CS can read out (FR-RET-03): every Noul whose probability is above the 0.5 undetermined point, with its
     * probability; when an L0 rule decided (fast lanes, policy denials) the rule's reason codes are listed with probability 1.
     */
    static List<ReasonCode> reasonCodes(DecisionResult risk) {
        List<ReasonCode> out = new ArrayList<>();
        if (risk.layer() == ai.intentoms.decision.model.Layer.L0_RULE && risk.reasonCodes() != null) {
            for (String code : risk.reasonCodes()) out.add(new ReasonCode(code, 1.0));
        }
        risk.answers().forEach((key, a) -> {
            if (QuestionDef.NOUL.equals(a.type()) && !key.endsWith("__l2")) {
                double p = a.asDouble();
                if (!Double.isNaN(p) && p > 0.5) out.add(new ReasonCode(key, Math.round(p * 1000) / 1000d));
            }
        });
        return out;
    }

    private static Map<String, Object> eligibilityPayload(Eligibility elig, String timing, Double score, String level, List<ReasonCode> codes, DecisionResult risk) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("eligible", elig.eligible());
        p.put("reasons", elig.reasons());
        p.put("windowDays", elig.windowDays());
        p.put("daysSinceDelivery", elig.daysSinceDelivery());
        p.put("finalSale", elig.finalSale());
        p.put("refundTiming", timing);
        p.put("riskScore", score);
        p.put("riskLevel", level);
        p.put("reasonCodes", codes.stream().map(c -> Map.of("code", c.code(), "probability", c.probability())).toList());
        p.put("decisionRecordId", risk.recordId());
        p.put("band", risk.band() == null ? null : risk.band().name());
        p.put("layer", risk.layer() == null ? null : risk.layer().name());
        p.put("action", risk.action());
        p.put("acts", risk.acts());
        p.put("provider", risk.provider());
        return p;
    }

    // ------------------------------------------------------------------------------------------------ FR-RET-05 keep-it

    /** Keep-it economics for an RMA's lines at the given risk score (tenant costs and recovery rate). */
    public KeepIt evaluateKeepIt(List<ReturnLine> lines, Double riskScore) {
        return KeepItEvaluator.evaluate(lines, riskScore,
            config.getDouble(ReturnsConfigKeys.RETURN_SHIPPING_COST_USD, ReturnsConfigKeys.DEFAULT_RETURN_SHIPPING_COST_USD),
            config.getDouble(ReturnsConfigKeys.RETURN_HANDLING_COST_USD, ReturnsConfigKeys.DEFAULT_RETURN_HANDLING_COST_USD),
            config.getDouble(ReturnsConfigKeys.RECOVERY_RATE, ReturnsConfigKeys.DEFAULT_RECOVERY_RATE),
            config.getBoolean(ReturnsConfigKeys.KEEP_IT_ENABLED, true));
    }

    /** Keep-it evaluation for an existing RMA (uses its stored risk score). */
    public KeepIt evaluateKeepIt(ReturnRma rma) { return evaluateKeepIt(rma.lines(), rma.riskScore()); }

    // ------------------------------------------------------------------------------------------- T3 human decisions

    /**
     * A person decides on a denial recommendation (T3, GR-13). {@code approved=true} confirms the denial: the RMA moves to
     * {@code rejected}. {@code approved=false} overrides it (recorded through {@code DecisionService.override}): the return
     * is approved with the conservative {@code on_inspection} timing, or — for an inspection-stage denial — refunded.
     */
    public ReturnRma applyDenial(String rmaId, boolean approved, String reason, Actor actor) {
        Actor who = actorOrCurrent(actor);
        requireReason(reason);
        DecideOutcome out = tx.execute(s -> {
            ReturnRma rma = returns.lock(rmaId);
            if (!ReturnTasks.TYPE_RETURN_DENIAL.equals(rma.reviewTaskType())) throw new ConflictException("Return " + rmaId + " has no denial recommendation pending");
            if (approved) {
                completeTask(rma.reviewTaskId(), "approved", reason);
                ReturnRma rejected = rejectTx(rma, reason, who, true);
                return new DecideOutcome(rejected, false);
            }
            if (rma.riskDecisionRecordId() != null) decisions.override(rma.riskDecisionRecordId(), "approve", reason);
            completeTask(rma.reviewTaskId(), "rejected", reason);
            audit.record("return.denial_overridden", ENTITY, rmaId, reason, Map.of("by", who.ref(), "decisionRecordId", nz(rma.riskDecisionRecordId())));
            Eligibility elig = rma.eligibility() == null ? new Eligibility(true, List.of("overridden_by_human"), null, null, false)
                : rma.eligibility().withEligible(true, concat(rma.eligibility().reasons(), "overridden_by_human"));
            if (ReturnStatus.REQUESTED.equals(rma.status())) {
                ReturnRma patched = rma.patch().eligibility(elig).refundTiming(RefundTiming.ON_INSPECTION).keepIt(new KeepIt(false, "human_override", null, null)).build();
                ReturnRma approvedRma = approve(patched, RefundTiming.ON_INSPECTION, null, who, rma.riskDecisionRecordId(), reason);
                Map<String, Object> payload = humanEligibilityPayload(elig, RefundTiming.ON_INSPECTION, rma, who, reason);
                append(approvedRma, ReturnEvents.ELIGIBILITY_DECIDED, payload, who, rma.riskDecisionRecordId());
                return new DecideOutcome(approvedRma, false);
            }
            ReturnRma patched = returns.update(rma.patch().eligibility(elig).reviewTask(null, null).build(), time.now());
            append(patched, ReturnEvents.ELIGIBILITY_DECIDED, humanEligibilityPayload(elig, patched.refundTiming(), rma, who, reason), who, rma.riskDecisionRecordId());
            boolean refundNow = ReturnStatus.INSPECTED.equals(patched.status()) && !patched.refunded() && isRefundOnInspection(patched.refundTiming());
            return new DecideOutcome(patched, refundNow);
        });
        if (out.refundNow()) return refund(rmaId, who);
        return out.rma();
    }

    /**
     * A person completes a {@code return_review} task by choosing the refund timing (T2 escalation, GR-13 override recorded
     * with the reason) or {@code reject}. Instant timing refunds immediately.
     */
    public ReturnRma applyReview(String rmaId, String refundTiming, String reason, Actor actor) {
        Actor who = actorOrCurrent(actor);
        requireReason(reason);
        if (refundTiming == null || refundTiming.isBlank()) throw new ValidationException("refundTiming is required: " + RefundTiming.ACTIONABLE + " or reject");
        String timing = refundTiming.trim().toLowerCase(Locale.ROOT);
        if (!RefundTiming.ACTIONABLE.contains(timing) && !"reject".equals(timing)) throw new ValidationException("Unknown refund timing '" + refundTiming + "'; expected " + RefundTiming.ACTIONABLE + " or reject");
        DecideOutcome out = tx.execute(s -> {
            ReturnRma rma = returns.lock(rmaId);
            if (!ReturnTasks.TYPE_RETURN_REVIEW.equals(rma.reviewTaskType())) throw new ConflictException("Return " + rmaId + " has no refund-timing review pending");
            if ("reject".equals(timing)) {
                completeTask(rma.reviewTaskId(), "reject", reason);
                return new DecideOutcome(rejectTx(rma, reason, who, false), false);
            }
            if (rma.riskDecisionRecordId() != null) decisions.override(rma.riskDecisionRecordId(), timing, reason);
            completeTask(rma.reviewTaskId(), timing, reason);
            audit.record("return.review_applied", ENTITY, rmaId, reason, Map.of("by", who.ref(), "refundTiming", timing, "decisionRecordId", nz(rma.riskDecisionRecordId())));
            Eligibility elig = rma.eligibility() == null ? new Eligibility(true, List.of(), null, null, false) : rma.eligibility();
            ReturnRma patched = rma.patch().eligibility(elig).refundTiming(timing).keepIt(new KeepIt(false, "human_review", null, null)).build();
            ReturnRma approvedRma = approve(patched, timing, null, who, rma.riskDecisionRecordId(), reason);
            append(approvedRma, ReturnEvents.ELIGIBILITY_DECIDED, humanEligibilityPayload(elig, timing, rma, who, reason), who, rma.riskDecisionRecordId());
            metrics.count("oms.returns.eligibility", "outcome", "human_review", "timing", timing);
            return new DecideOutcome(approvedRma, RefundTiming.INSTANT.equals(timing));
        });
        if (out.refundNow()) return refund(rmaId, who);
        return out.rma();
    }

    private Map<String, Object> humanEligibilityPayload(Eligibility elig, String timing, ReturnRma rma, Actor who, String reason) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("eligible", elig.eligible());
        p.put("reasons", elig.reasons());
        p.put("windowDays", elig.windowDays());
        p.put("daysSinceDelivery", elig.daysSinceDelivery());
        p.put("refundTiming", timing);
        p.put("riskScore", rma.riskScore());
        p.put("riskLevel", rma.riskLevel());
        p.put("reasonCodes", rma.reasonCodes().stream().map(c -> Map.of("code", c.code(), "probability", c.probability())).toList());
        p.put("decisionRecordId", rma.riskDecisionRecordId());
        p.put("layer", "L3_HUMAN");
        p.put("band", null);
        p.put("decidedBy", who.ref());
        p.put("reason", reason);
        return p;
    }

    // ------------------------------------------------------------------------------------ carrier scan / receive / inspect

    /** Carrier scan of the return package: appends {@code return.carrier_scanned}; refunds when the timing is {@code on_carrier_scan}. */
    public ReturnRma recordScan(String rmaId, Actor actor) {
        Actor who = actorOrCurrent(actor);
        ReturnRma rma = tx.execute(s -> {
            ReturnRma r = returns.lock(rmaId);
            if (ReturnStatus.REJECTED.equals(r.status()) || ReturnStatus.CANCELLED.equals(r.status())) throw new ConflictException("Return " + rmaId + " is " + r.status());
            if (ReturnStatus.REQUESTED.equals(r.status())) throw new ConflictException("Return " + rmaId + " has not been approved yet");
            Instant now = time.now();
            ReturnRma updated = returns.update(r.patch().scannedAt(r.scannedAt() == null ? now : r.scannedAt()).build(), now);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("scannedAt", now.toString());
            payload.put("refundTiming", r.refundTiming());
            append(updated, ReturnEvents.CARRIER_SCANNED, payload, who, null);
            return updated;
        });
        if (RefundTiming.ON_CARRIER_SCAN.equals(rma.refundTiming()) && !rma.refunded() && (rma.refund() == null || !rma.refund().awaitingApproval())) return refund(rmaId, who);
        return rma;
    }

    /** Goods received at a node (warehouse or store): {@code event.received}; records only when the RMA was already refunded. */
    public ReturnRma receive(String rmaId, String nodeId, Actor actor) {
        Actor who = actorOrCurrent(actor);
        return tx.execute(s -> {
            ReturnRma r = returns.lock(rmaId);
            if (ReturnStatus.REJECTED.equals(r.status()) || ReturnStatus.CANCELLED.equals(r.status())) throw new ConflictException("Return " + rmaId + " is " + r.status());
            if (ReturnStatus.REQUESTED.equals(r.status())) throw new ConflictException("Return " + rmaId + " has not been approved yet");
            if (r.receivedAt() != null) throw new ConflictException("Return " + rmaId + " was already received at " + r.receivedNodeId());
            if (ReturnStatus.APPROVED.equals(r.status())) fire(r, "event.label_issued", Map.of(), who, null);
            TransitionResult tr = fire(r, "event.received", Map.of(), who, null);
            if (!tr.applied() && !r.isTerminal()) throw new ConflictException("Return " + rmaId + " cannot be received: " + tr.blockedBy(), Map.of("blockedBy", tr.blockedBy()));
            Instant now = time.now();
            ReturnRma updated = returns.update(r.patch().received(now, nodeId).status(machine.status(ENTITY, rmaId).state()).build(), now);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("nodeId", nodeId);
            payload.put("receivedAt", now.toString());
            payload.put("transitionApplied", tr.applied());
            append(updated, ReturnEvents.RECEIVED, payload, who, null);
            metrics.count("oms.returns.received");
            return updated;
        });
    }

    /**
     * Inspection outcome: {@code event.inspected}, condition stored on RMA and lines. With timing {@code on_inspection} /
     * {@code store_credit_only} an acceptable condition ({@code returns.refundable_conditions}) triggers the refund; an
     * unacceptable one opens a T3 {@code return_denial} recommendation. Disposition (FR-RET-06) runs afterwards.
     */
    public ReturnRma inspect(String rmaId, String condition, String note, Actor actor) {
        Actor who = actorOrCurrent(actor);
        String cond = ItemCondition.normalise(condition);
        DecideOutcome out = tx.execute(s -> {
            ReturnRma r = returns.lock(rmaId);
            if (r.receivedAt() == null && !ReturnStatus.RECEIVED.equals(r.status())) throw new ConflictException("Return " + rmaId + " must be received before inspection (status " + r.status() + ")");
            if (r.inspectedAt() != null) throw new ConflictException("Return " + rmaId + " was already inspected (" + r.condition() + ")");
            TransitionResult tr = fire(r, "event.inspected", Map.of(), who, null);
            if (!tr.applied() && !r.isTerminal()) throw new ConflictException("Return " + rmaId + " cannot be inspected: " + tr.blockedBy(), Map.of("blockedBy", tr.blockedBy()));
            Instant now = time.now();
            List<ReturnLine> lines = r.lines().stream().map(l -> l.withCondition(cond)).toList();
            ReturnRma updated = returns.update(r.patch().condition(cond).inspected(now, note).lines(lines).status(machine.status(ENTITY, rmaId).state()).build(), now);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("condition", cond);
            payload.put("note", note);
            payload.put("inspectedAt", now.toString());
            payload.put("rmaId", rmaId);
            payload.put("orderId", updated.orderId());
            payload.put("fraudConfirmed", ItemCondition.isFraud(cond)); // FD-06: the grading labels this return's return_risk decision
            append(updated, ReturnEvents.INSPECTED, payload, who, null);
            metrics.count("oms.returns.inspected", "condition", cond);
            boolean gated = isRefundOnInspection(updated.refundTiming()) && !updated.refunded() && (updated.refund() == null || !updated.refund().awaitingApproval());
            if (gated && !refundableConditions().contains(cond)) {
                Map<String, Object> taskPayload = new LinkedHashMap<>();
                taskPayload.put("rmaId", rmaId);
                taskPayload.put("orderId", updated.orderId());
                taskPayload.put("recommendation", "reject");
                taskPayload.put("reasons", List.of("condition_unacceptable"));
                taskPayload.put("condition", cond);
                taskPayload.put("note", note);
                HumanTask task = tasks.create(new TaskRequest(ReturnTasks.TYPE_RETURN_DENIAL, ReturnTasks.QUEUE_RISK_REVIEW, ENTITY, rmaId,
                    "Return " + rmaId + " inspected as " + cond + ": confirm rejection", taskPayload, REVIEW_SLA, Set.of("returns_risk"), 4, null));
                Eligibility elig = updated.eligibility() == null ? new Eligibility(false, List.of("condition_unacceptable"), null, null, false)
                    : updated.eligibility().withEligible(false, concat(updated.eligibility().reasons(), "condition_unacceptable"));
                updated = returns.update(updated.patch().eligibility(elig).reviewTask(task.id(), ReturnTasks.TYPE_RETURN_DENIAL).build(), now);
                append(updated, ReturnEvents.REVIEW_REQUESTED, Map.of("taskId", task.id(), "type", ReturnTasks.TYPE_RETURN_DENIAL, "queue", ReturnTasks.QUEUE_RISK_REVIEW, "reason", "condition_unacceptable"), who, null);
                return new DecideOutcome(updated, false);
            }
            return new DecideOutcome(updated, gated);
        });
        if (out.refundNow()) refund(rmaId, who);
        return disposition(rmaId, who);
    }

    private boolean isRefundOnInspection(String timing) {
        return RefundTiming.ON_INSPECTION.equals(timing) || RefundTiming.STORE_CREDIT_ONLY.equals(timing);
    }

    private List<String> refundableConditions() {
        List<Object> configured = config.getList(ReturnsConfigKeys.REFUNDABLE_CONDITIONS);
        if (configured.isEmpty()) return ReturnsConfigKeys.DEFAULT_REFUNDABLE_CONDITIONS;
        return configured.stream().map(String::valueOf).toList();
    }

    // -------------------------------------------------------------------------------------------- FR-RET-06 disposition

    /**
     * {@code return_disposition} (T1): acts on the model's choice in the ACT band; otherwise defaults {@code restock} for
     * new / like-new and {@code refurbish} for damaged goods, and opens a {@code return_disposition} task in
     * {@code returns_ops} for anything else.
     */
    public ReturnRma disposition(String rmaId, Actor actor) {
        Actor who = actorOrCurrent(actor);
        return tx.execute(s -> {
            ReturnRma r = returns.lock(rmaId);
            if (r.condition() == null) throw new ConflictException("Return " + rmaId + " must be inspected before disposition");
            Order order = orders.get(r.orderId());
            ReturnLine first = r.lines().get(0);
            double recoveryRate = config.getDouble(ReturnsConfigKeys.RECOVERY_RATE, ReturnsConfigKeys.DEFAULT_RECOVERY_RATE);
            Map<String, Object> ret = new LinkedHashMap<>();
            ret.put("reason", nz(r.reasonCode()));
            ret.put("item_condition", r.condition());
            ret.put("inspection_notes", FreeTextRedactor.redact(nz(r.inspectionNote()), ai.intentoms.order.model.OrderPii.known(order)));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sku", first.sku());
            item.put("category", nz(first.attributes().get("category")));
            item.put("cost_usd", cogs(r.lines()).doubleValue());
            item.put("resale_value_usd", KeepItEvaluator.recoveryValue(r.lines(), recoveryRate, r.condition()).doubleValue());
            item.put("vendor_return_eligible", first.flag("vendor_return_eligible") || ("defective".equals(r.reasonCode()) && first.flag("vendor_returns")));
            item.put("hazmat", r.lines().stream().anyMatch(l -> l.flag("hazmat")));
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("return", ret);
            input.put("item", item);
            input.put("order", Map.of("id", order.id(), "channel", order.channel()));
            DecisionResult d = decisions.decide(DECISION_DISPOSITION, input, DecisionOptions.live(ENTITY, rmaId, who));
            String value = d.value() == null ? null : d.value().toString();
            String disp;
            String source;
            String taskId = null;
            if (d.acts() && value != null && Disposition.ALL.contains(value)) { disp = value; source = "decision"; }
            else if (ItemCondition.NEW.equals(r.condition()) || ItemCondition.LIKE_NEW.equals(r.condition())) { disp = Disposition.RESTOCK; source = "default"; }
            else if (ItemCondition.DAMAGED.equals(r.condition())) { disp = Disposition.REFURBISH; source = "default"; }
            else {
                disp = null;
                source = "human_task";
                Map<String, Object> taskPayload = new LinkedHashMap<>();
                taskPayload.put("rmaId", rmaId);
                taskPayload.put("orderId", r.orderId());
                taskPayload.put("condition", r.condition());
                taskPayload.put("note", r.inspectionNote());
                taskPayload.put("recommendation", value);
                taskPayload.put("decisionRecordId", d.recordId());
                taskPayload.put("options", new ArrayList<>(Disposition.ALL));
                taskId = tasks.create(new TaskRequest(ReturnTasks.TYPE_DISPOSITION_REVIEW, ReturnTasks.QUEUE_RETURNS_OPS, ENTITY, rmaId,
                    "Choose disposition for return " + rmaId + " (" + r.condition() + ")", taskPayload, REVIEW_SLA, Set.of("returns_ops"), 2, null)).id();
            }
            Instant now = time.now();
            String finalDisp = disp;
            List<ReturnLine> lines = r.lines().stream().map(l -> l.withDisposition(finalDisp)).toList();
            ReturnRma updated = returns.update(r.patch().disposition(disp, d.recordId()).lines(lines).build(), now);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("disposition", disp);
            payload.put("source", source);
            payload.put("decisionRecordId", d.recordId());
            payload.put("recommendedValue", value);
            payload.put("band", d.band() == null ? null : d.band().name());
            payload.put("layer", d.layer() == null ? null : d.layer().name());
            payload.put("acts", d.acts());
            payload.put("taskId", taskId);
            payload.put("condition", r.condition());
            append(updated, ReturnEvents.DISPOSITIONED, payload, who, d.recordId());
            metrics.count("oms.returns.disposition", "value", disp == null ? "pending" : disp, "source", source);
            return updated;
        });
    }

    /** A person sets the disposition for a {@code return_disposition} task (T1 escalation). */
    public ReturnRma applyDisposition(String rmaId, String disposition, String reason, Actor actor) {
        Actor who = actorOrCurrent(actor);
        requireReason(reason);
        if (disposition == null || !Disposition.ALL.contains(disposition.trim().toLowerCase(Locale.ROOT))) throw new ValidationException("disposition must be one of " + Disposition.ALL);
        String disp = disposition.trim().toLowerCase(Locale.ROOT);
        return tx.execute(s -> {
            ReturnRma r = returns.lock(rmaId);
            if (r.condition() == null) throw new ConflictException("Return " + rmaId + " must be inspected before disposition");
            if (r.dispositionDecisionRecordId() != null) decisions.override(r.dispositionDecisionRecordId(), disp, reason);
            audit.record("return.disposition_set", ENTITY, rmaId, reason, Map.of("by", who.ref(), "disposition", disp));
            Instant now = time.now();
            ReturnRma updated = returns.update(r.patch().disposition(disp, r.dispositionDecisionRecordId()).lines(r.lines().stream().map(l -> l.withDisposition(disp)).toList()).build(), now);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("disposition", disp);
            payload.put("source", "human");
            payload.put("decisionRecordId", r.dispositionDecisionRecordId());
            payload.put("decidedBy", who.ref());
            payload.put("reason", reason);
            append(updated, ReturnEvents.DISPOSITIONED, payload, who, r.dispositionDecisionRecordId());
            return updated;
        });
    }

    private static BigDecimal cogs(List<ReturnLine> lines) {
        BigDecimal sum = BigDecimal.ZERO;
        for (ReturnLine l : lines) {
            Object c = l.attributes().get("cogs");
            if (c instanceof Number n) sum = sum.add(BigDecimal.valueOf(n.doubleValue()).multiply(BigDecimal.valueOf(l.qty())));
        }
        return sum.setScale(2, Money.ROUNDING);
    }

    // ---------------------------------------------------------------------------------------------- FR-RET-04 refund

    private record RefundPlan(ReturnRma rma, RefundInfo info, boolean held) {}

    /**
     * Refunds the RMA: merchandise + tax share (+ shipping share when configured) − restocking fee, as store credit for
     * {@code store_credit_only} or through finance in original-tender proportion. Amounts above
     * {@code returns.refund_requires_approval_above_usd} and GR-05 SpendGuard refusals wait in {@code refund_approvals}
     * (status {@code awaiting_approval}) for {@link #approveRefund}. On success fires {@code event.refund_issued}
     * ({@code ctx.refund_timing}) or {@code decision.keep_it} and appends {@code return.refunded}.
     */
    public ReturnRma refund(String rmaId, Actor actor) { return doRefund(rmaId, actorOrCurrent(actor), false); }

    /** Finance approves a held refund (T3): records the approver, completes the task and retries the money movement. */
    public ReturnRma approveRefund(String rmaId, Actor actor) {
        Actor who = actorOrCurrent(actor);
        tx.execute(s -> {
            ReturnRma r = returns.lock(rmaId);
            if (r.refund() == null || !r.refund().awaitingApproval()) throw new ConflictException("Return " + rmaId + " has no refund awaiting approval");
            completeTask(r.refund().approvalTaskId(), "approved", "Refund approved by " + who.ref());
            Instant now = time.now();
            ReturnRma updated = returns.update(r.patch().refund(r.refund().approved(who.ref())).reviewTask(null, null).build(), now);
            audit.record("return.refund_approved", ENTITY, rmaId, "refund approval", Map.of("by", who.ref(), "amount", r.refund().amount(), "currency", nz(r.refund().currency()), "holdReason", nz(r.refund().holdReason())));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("amount", r.refund().amount());
            payload.put("currency", r.refund().currency());
            payload.put("approvedBy", who.ref());
            payload.put("holdReason", r.refund().holdReason());
            append(updated, ReturnEvents.REFUND_APPROVED, payload, who, null);
            return updated;
        });
        return doRefund(rmaId, who, true);
    }

    private ReturnRma doRefund(String rmaId, Actor who, boolean approved) {
        RefundPlan plan = tx.execute(s -> planRefund(rmaId, who, approved));
        if (plan.held()) return plan.rma();
        RefundInfo info = plan.info();
        Money amount = new Money(info.amount(), info.currency());
        List<RefundLeg> legs;
        String status;
        if (RefundInfo.STORE_CREDIT.equals(info.method())) {
            StoreCredit credit = storeCredit.issue(plan.rma().customerRef(), amount, rmaId, plan.rma().orderId(), "return " + rmaId, who);
            legs = List.of(new RefundLeg(credit.id(), RefundInfo.STORE_CREDIT, credit.amount().amount(), "issued"));
            status = RefundInfo.SUCCEEDED;
        } else {
            List<Refund> out;
            try {
                out = payments.refund(plan.rma().orderId(), amount, "return " + rmaId, rmaId, who);
            } catch (GuardrailViolationException e) {
                log.warn("GR-05 refused refund for return {}: {}", rmaId, e.getMessage());
                return tx.execute(s -> holdRefund(rmaId, info, "guardrail", e.details(), who));
            }
            legs = out.stream().map(r -> new RefundLeg(r.id(), r.paymentId(), r.amount().amount(), r.status().name().toLowerCase(Locale.ROOT))).toList();
            status = !out.isEmpty() && out.stream().allMatch(r -> r.status() == RefundStatus.SUCCEEDED) ? RefundInfo.SUCCEEDED : RefundInfo.FAILED;
        }
        List<RefundLeg> finalLegs = legs;
        String finalStatus = status;
        return tx.execute(s -> recordRefund(rmaId, info, finalLegs, finalStatus, who));
    }

    private RefundPlan planRefund(String rmaId, Actor who, boolean approved) {
        ReturnRma rma = returns.lock(rmaId);
        if (rma.refunded()) throw new ConflictException("Return " + rmaId + " was already refunded");
        if (ReturnStatus.REJECTED.equals(rma.status()) || ReturnStatus.CANCELLED.equals(rma.status())) throw new ConflictException("Return " + rmaId + " is " + rma.status());
        if (ReturnStatus.REQUESTED.equals(rma.status())) throw new ConflictException("Return " + rmaId + " eligibility has not been decided");
        if (rma.refund() != null && rma.refund().awaitingApproval() && !approved) throw new ConflictException("Refund for " + rmaId + " is awaiting approval (task " + rma.refund().approvalTaskId() + ")");
        if (RefundTiming.ESCALATE.equals(rma.refundTiming())) throw new ConflictException("Return " + rmaId + " is awaiting refund-timing review");
        Order order = orders.get(rma.orderId());
        String method = RefundTiming.STORE_CREDIT_ONLY.equals(rma.refundTiming()) ? RefundInfo.STORE_CREDIT : RefundInfo.ORIGINAL_TENDER;
        double pct = config.getDouble(ReturnsConfigKeys.RESTOCKING_FEE_PCT, 0.0);
        List<Object> feeConditions = config.getList(ReturnsConfigKeys.RESTOCKING_FEE_CONDITIONS);
        List<String> feeOn = feeConditions.isEmpty() ? ReturnsConfigKeys.DEFAULT_RESTOCKING_FEE_CONDITIONS : feeConditions.stream().map(String::valueOf).toList();
        boolean feeApplies = rma.condition() != null && feeOn.contains(rma.condition());
        RefundInfo computed = RefundCalculator.compute(rma.lines(), order, pct, feeApplies, config.getBoolean(ReturnsConfigKeys.REFUND_SHIPPING, false), method);
        RefundInfo info = rma.refund() == null ? computed
            : new RefundInfo(computed.amount(), computed.currency(), computed.restockingFee(), computed.taxRefunded(), computed.shippingRefunded(), method, List.of(), RefundInfo.PENDING,
                rma.refund().approvalTaskId(), rma.refund().holdReason(), rma.refund().approvedBy(), null);
        if (info.amount().signum() <= 0) throw new ValidationException("Nothing to refund on return " + rmaId);
        double threshold = config.getDouble(ReturnsConfigKeys.REFUND_REQUIRES_APPROVAL_ABOVE_USD, ReturnsConfigKeys.DEFAULT_REFUND_APPROVAL_THRESHOLD_USD);
        if (!approved && info.approvedBy() == null && info.amount().doubleValue() > threshold) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("thresholdUsd", threshold);
            details.put("amount", info.amount());
            details.put("tier", "T3");
            return new RefundPlan(holdRefund(rmaId, info, "above_threshold", details, who), info, true);
        }
        ReturnRma updated = returns.update(rma.patch().refund(info).build(), time.now());
        return new RefundPlan(updated, info, false);
    }

    private ReturnRma holdRefund(String rmaId, RefundInfo info, String reason, Map<String, Object> details, Actor who) {
        ReturnRma rma = returns.lock(rmaId);
        Map<String, Object> taskPayload = new LinkedHashMap<>();
        taskPayload.put("rmaId", rmaId);
        taskPayload.put("orderId", rma.orderId());
        taskPayload.put("customerRef", rma.customerRef());
        taskPayload.put("amount", info.amount());
        taskPayload.put("currency", info.currency());
        taskPayload.put("method", info.method());
        taskPayload.put("holdReason", reason);
        taskPayload.put("details", details == null ? Map.of() : details);
        HumanTask task = tasks.create(new TaskRequest(ReturnTasks.TYPE_REFUND_APPROVAL, ReturnTasks.QUEUE_REFUND_APPROVALS, ENTITY, rmaId,
            "Approve refund " + info.amount() + " " + info.currency() + " for return " + rmaId + " (" + reason + ")", taskPayload, REFUND_APPROVAL_SLA, Set.of("finance"), 4, null));
        Instant now = time.now();
        ReturnRma updated = returns.update(rma.patch().refund(info.awaiting(task.id(), reason)).reviewTask(task.id(), ReturnTasks.TYPE_REFUND_APPROVAL).build(), now);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("amount", info.amount());
        payload.put("currency", info.currency());
        payload.put("reason", reason);
        payload.put("details", details == null ? Map.of() : details);
        payload.put("taskId", task.id());
        payload.put("queue", ReturnTasks.QUEUE_REFUND_APPROVALS);
        append(updated, ReturnEvents.REFUND_HELD, payload, who, null);
        metrics.count("oms.returns.refund_held", "reason", reason);
        return updated;
    }

    private ReturnRma recordRefund(String rmaId, RefundInfo info, List<RefundLeg> legs, String status, Actor who) {
        ReturnRma rma = returns.lock(rmaId);
        Instant now = time.now();
        if (!RefundInfo.SUCCEEDED.equals(status)) {
            ReturnRma updated = returns.update(rma.patch().refund(info.issued(legs, RefundInfo.FAILED, null)).build(), now);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("amount", info.amount());
            payload.put("currency", info.currency());
            payload.put("legs", legMaps(legs));
            append(updated, ReturnEvents.REFUND_FAILED, payload, who, null);
            metrics.count("oms.returns.refund_failed");
            return updated;
        }
        TransitionResult tr;
        if (rma.keepItOffered()) tr = fire(rma, "decision.keep_it", Map.of("decision", Map.of("value", "keep_it")), who, null);
        else {
            Map<String, Object> ctx = new LinkedHashMap<>();
            ctx.put("refund_timing", nz(rma.refundTiming()));
            tr = fire(rma, "event.refund_issued", Map.of("ctx", ctx), who, null);
        }
        if (!tr.applied()) log.info("Return {} refunded while in {}; state unchanged ({})", rmaId, rma.status(), tr.blockedBy());
        ReturnRma updated = returns.update(rma.patch().refund(info.issued(legs, RefundInfo.SUCCEEDED, now)).refundedAt(now).reviewTask(null, null)
            .status(machine.status(ENTITY, rmaId).state()).build(), now);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("amount", info.amount());
        payload.put("currency", info.currency());
        payload.put("restockingFee", info.restockingFee());
        payload.put("taxRefunded", info.taxRefunded());
        payload.put("shippingRefunded", info.shippingRefunded());
        payload.put("method", info.method());
        payload.put("legs", legMaps(legs));
        payload.put("timing", rma.refundTiming());
        payload.put("keepIt", rma.keepItOffered());
        payload.put("approvedBy", info.approvedBy());
        payload.put("transitionApplied", tr.applied());
        payload.put("toState", updated.status());
        append(updated, ReturnEvents.REFUNDED, payload, who, null);
        metrics.count("oms.returns.refunded", "method", info.method(), "timing", nz(rma.refundTiming()));
        metrics.count("oms.returns.refund_amount", info.amount().doubleValue(), "method", info.method());
        return updated;
    }

    private static List<Map<String, Object>> legMaps(List<RefundLeg> legs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RefundLeg l : legs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("refundId", l.refundId());
            m.put("paymentId", l.paymentId());
            m.put("amount", l.amount());
            m.put("status", l.status());
            out.add(m);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ reject / cancel

    /** Human rejection ({@code api.reject_return}, permission return.decide, reason mandatory: GR-13). */
    public ReturnRma reject(String rmaId, String reason, Actor actor) {
        Actor who = actorOrCurrent(actor);
        requireReason(reason);
        return tx.execute(s -> rejectTx(returns.lock(rmaId), reason, who, false));
    }

    private ReturnRma rejectTx(ReturnRma rma, String reason, Actor who, boolean recommendationConfirmed) {
        if (rma.isTerminal()) throw new ConflictException("Return " + rma.rmaId() + " is already " + rma.status());
        if (rma.refunded()) throw new ConflictException("Return " + rma.rmaId() + " was refunded and cannot be rejected");
        TransitionResult tr;
        if (ReturnStatus.REQUESTED.equals(rma.status())) tr = fire(rma, "decision.return_eligibility", Map.of("decision", Map.of("value", "reject"), "approval", Map.of("present", true)), who, rma.riskDecisionRecordId());
        else tr = fire(rma, "api.reject_return", Map.of(), who, null);
        if (!tr.applied()) throw new ConflictException("Return " + rma.rmaId() + " cannot be rejected from " + rma.status() + ": " + tr.blockedBy(), Map.of("blockedBy", tr.blockedBy()));
        cancelTaskQuietly(rma.reviewTaskId(), "return rejected: " + reason);
        Instant now = time.now();
        ReturnRma updated = returns.update(rma.patch().status(machine.status(ENTITY, rma.rmaId()).state()).reviewTask(null, null).build(), now);
        audit.record("return.rejected", ENTITY, rma.rmaId(), reason, Map.of("by", who.ref(), "recommendationConfirmed", recommendationConfirmed));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", reason);
        payload.put("rejectedBy", who.ref());
        payload.put("recommendationConfirmed", recommendationConfirmed);
        payload.put("policyReasons", rma.eligibility() == null ? List.of() : rma.eligibility().reasons());
        payload.put("decisionRecordId", rma.riskDecisionRecordId());
        append(updated, ReturnEvents.REJECTED, payload, who, rma.riskDecisionRecordId());
        metrics.count("oms.returns.rejected");
        return updated;
    }

    /** Customer or CS cancels the return before it is received ({@code api.cancel_return}). */
    public ReturnRma cancel(String rmaId, String reason, Actor actor) {
        Actor who = actorOrCurrent(actor);
        return tx.execute(s -> {
            ReturnRma rma = returns.lock(rmaId);
            if (rma.isTerminal()) throw new ConflictException("Return " + rmaId + " is already " + rma.status());
            if (rma.refunded()) throw new ConflictException("Return " + rmaId + " was refunded and cannot be cancelled");
            TransitionResult tr = fire(rma, "api.cancel_return", Map.of(), who, null);
            if (!tr.applied()) throw new ConflictException("Return " + rmaId + " cannot be cancelled from " + rma.status() + ": " + tr.blockedBy(), Map.of("blockedBy", tr.blockedBy()));
            cancelTaskQuietly(rma.reviewTaskId(), "return cancelled");
            Instant now = time.now();
            ReturnRma updated = returns.update(rma.patch().status(machine.status(ENTITY, rmaId).state()).reviewTask(null, null).build(), now);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("reason", reason);
            payload.put("cancelledBy", who.ref());
            append(updated, ReturnEvents.CANCELLED, payload, who, null);
            metrics.count("oms.returns.cancelled");
            return updated;
        });
    }

    // -------------------------------------------------------------------------------------------------------- reads

    public ReturnRma getRma(String rmaId) { return returns.get(rmaId); }

    /** UI contract item 11 view: RMA + lines + order summary fields + ledger timeline of aggregate {@code return/<rma>}. */
    public ReturnRecord get(String rmaId) {
        ReturnRma r = returns.get(rmaId);
        Optional<Order> order = orders.find(r.orderId());
        String currency = r.lines().isEmpty() ? order.map(Order::currency).orElse("USD") : r.lines().get(0).unitPrice().currency();
        Money merch = Money.zero(currency);
        for (ReturnLine l : r.lines()) merch = merch.add(l.extended());
        List<ReturnRecord.Line> lines = r.lines().stream().map(l -> new ReturnRecord.Line(l.lineId(), l.sku(), l.description(), l.qty(), l.unitPrice(), l.tax(), l.reasonCode(),
            l.customerReasonText(), l.condition(), l.disposition())).toList();
        ReturnRecord.EligibilityView elig = r.eligibility() == null ? null
            : new ReturnRecord.EligibilityView(r.eligibility().eligible(), r.eligibility().reasons(), r.eligibility().windowDays(), r.eligibility().daysSinceDelivery(), r.eligibility().finalSale());
        ReturnRecord.RefundView refund = null;
        Money refundAmount = null;
        if (r.refund() != null) {
            String rc = r.refund().currency() == null ? currency : r.refund().currency();
            refundAmount = r.refund().amount() == null ? null : new Money(r.refund().amount(), rc);
            refund = new ReturnRecord.RefundView(refundAmount, money(r.refund().restockingFee(), rc), money(r.refund().taxRefunded(), rc), money(r.refund().shippingRefunded(), rc),
                r.refund().method(), r.refund().status(), r.refund().legs(), r.refund().approvalTaskId(), r.refund().holdReason(), r.refund().approvedBy(), r.refund().issuedAt());
        }
        ReturnRecord.KeepItView keepIt = r.keepIt() == null ? null
            : new ReturnRecord.KeepItView(r.keepIt().offered(), r.keepIt().offered(), r.keepIt().reason(), money(r.keepIt().returnCostUsd(), "USD"), money(r.keepIt().recoveryValueUsd(), "USD"));
        List<ReturnRecord.TimelineEntry> timeline = new ArrayList<>();
        for (Event e : events.load(ENTITY, rmaId)) {
            Object to = e.payload().get("to");
            timeline.add(new ReturnRecord.TimelineEntry(e.occurredAt(), kind(e.type()), e.type(), to == null ? null : to.toString(), e.actor() == null ? null : e.actor().ref(), e.payload()));
        }
        return new ReturnRecord(r.rmaId(), r.orderId(), order.map(Order::channelOrderId).orElse(null), order.map(Order::channel).orElse(null), r.channel(), r.customerRef(), r.status(),
            r.reasonCode(), r.customerReasonText(), lines, r.createdAt(), r.createdBy(), r.riskScore(), r.riskLevel(), r.reasonCodes(), r.riskDecisionRecordId(), r.reasonDecisionRecordId(),
            r.refundTiming(), r.refundTimingDecisionRecordId(), r.disposition(), r.dispositionDecisionRecordId(), elig, refund, keepIt, r.condition(), r.inspectionNote(), r.receiptPresent(),
            r.storeNodeId(), r.agentId(), r.mandateId(), r.reviewTaskId(), r.reviewTaskType(), r.scannedAt(), r.receivedAt(), r.receivedNodeId(), r.inspectedAt(), r.refundedAt(),
            refundAmount == null ? merch : refundAmount, merch, timeline);
    }

    public Page<ReturnSummary> list(ReturnQuery q) { return returns.list(q == null ? new ReturnQuery(null, null, null, null, null, 0, 50) : q); }

    public List<ReturnSummary> forOrder(String orderId) { return returns.summariesForOrder(orderId); }

    private static String kind(String type) {
        if (type.endsWith(".transitioned") || type.endsWith(".state_initialized")) return "transition";
        if (type.startsWith("decision.")) return "decision";
        if (type.startsWith("task.")) return "task";
        return "event";
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    private TransitionResult fire(ReturnRma rma, String trigger, Map<String, Object> vars, Actor who, String causationId) {
        return machine.fire(new TransitionRequest(ENTITY, rma.rmaId(), trigger, null, null, vars, who, rma.orderId(), causationId));
    }

    private void append(ReturnRma rma, String type, Map<String, Object> payload, Actor who, String causationId) {
        if (ReturnEvents.DISPOSITIONED.equals(type) || ReturnEvents.REFUNDED.equals(type)) payload = outcomeFacts(rma, payload);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("orderId", rma.orderId());
        meta.put("channel", rma.channel());
        if (rma.customerRef() != null) meta.put("customerRef", rma.customerRef());
        events.append(ENTITY, rma.rmaId(), -1, List.of(new NewEvent(type, payload, who, causationId, rma.orderId(), meta)));
    }

    /**
     * OC-03 / FD-06: what the self-optimizing layer needs to score the decisions behind the order this return came back
     * from. {@code fraudConfirmed} comes from return grading: an inspection that found a different item than the one
     * sold ({@code wrong_item}) or an item that is not what the buyer described ({@code not_as_described}) is confirmed
     * return fraud (see {@link ItemCondition#isFraud}). {@code refundAmount} is the refund issued, or the value of
     * the returned lines while the refund is still pending.
     */
    static Map<String, Object> outcomeFacts(ReturnRma rma, Map<String, Object> payload) {
        Map<String, Object> p = new LinkedHashMap<>(payload);
        p.putIfAbsent("orderId", rma.orderId());
        p.put("rmaId", rma.rmaId()); // keys the return's single outcome: its disposition and refund events merge into one
        if (rma.customerRef() != null) p.putIfAbsent("customerRef", rma.customerRef());
        if (rma.reasonCode() != null) p.putIfAbsent("reasonCode", rma.reasonCode());
        if (rma.condition() != null) p.putIfAbsent("condition", rma.condition());
        p.putIfAbsent("disposition", rma.disposition());
        p.putIfAbsent("rmaId", rma.rmaId());
        p.put("fraudConfirmed", ItemCondition.isFraud(rma.condition()));
        // §2 perfect-order rate: which lines came back, with each line's own reason and inspection grading.
        List<Map<String, Object>> returned = new ArrayList<>();
        for (ReturnLine l : rma.lines()) {
            Map<String, Object> rl = new LinkedHashMap<>();
            rl.put("lineId", l.lineId());
            rl.put("reasonCode", l.reasonCode() != null ? l.reasonCode() : rma.reasonCode());
            rl.put("condition", l.condition() != null ? l.condition() : rma.condition());
            returned.add(rl);
        }
        p.putIfAbsent("returnLines", returned);
        p.putIfAbsent("refundAmount", refundValue(rma));
        return p;
    }

    private static double refundValue(ReturnRma rma) {
        if (rma.refund() != null && rma.refund().amount() != null && RefundInfo.SUCCEEDED.equals(rma.refund().status())) return rma.refund().amount().doubleValue();
        double v = 0;
        for (ReturnLine l : rma.lines()) if (l.unitPrice() != null && l.unitPrice().amount() != null) v += l.unitPrice().amount().doubleValue() * l.qty();
        return Math.round(v * 100) / 100.0;
    }

    private void completeTask(String taskId, String outcome, String reason) {
        if (taskId == null) return;
        try {
            HumanTask t = tasks.get(taskId);
            if (t.isOpen()) tasks.complete(taskId, outcome, reason, Map.of());
        } catch (NotFoundException | ConflictException e) {
            log.debug("Task {} not completed: {}", taskId, e.getMessage());
        }
    }

    private void cancelTaskQuietly(String taskId, String reason) {
        if (taskId == null) return;
        try {
            HumanTask t = tasks.get(taskId);
            if (t.isOpen()) tasks.cancel(taskId, reason);
        } catch (NotFoundException | ConflictException e) {
            log.debug("Task {} not cancelled: {}", taskId, e.getMessage());
        }
    }

    private Actor actorOrCurrent(Actor actor) { return actor != null ? actor : Actor.parse(authz.actorRef()); }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) throw new ValidationException("reason is required (GR-13: every human decision carries a reason)");
    }

    private static List<String> concat(List<String> list, String extra) {
        List<String> out = new ArrayList<>(list);
        if (!out.contains(extra)) out.add(extra);
        return out;
    }

    private static Money money(BigDecimal amount, String currency) { return amount == null ? null : new Money(amount, currency); }

    private static String nz(Object o) { return o == null ? "" : o.toString(); }
}
