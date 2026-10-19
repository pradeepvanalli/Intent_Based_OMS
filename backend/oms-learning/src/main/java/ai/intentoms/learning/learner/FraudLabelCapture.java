package ai.intentoms.learning.learner;

import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.record.DecisionEscalationRepository;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.ledger.EventSubscriber;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.workflow.task.HumanTask;
import ai.intentoms.workflow.task.HumanTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * FD-06 (and the FD-02 refused-delivery label): writes {@code fraud_outcome} on each fraud decision of an order with
 * what actually happened, so the fraud learners measure false positives and negatives against ground truth. Each event
 * labels only the decision it is evidence for:
 * <ul>
 *   <li>{@code payment.chargeback} → {@code payment_risk}: confirmed when the gateway reason is fraud (unauthorised),
 *       a non-fraud dispute is recorded but does not confirm fraud;</li>
 *   <li>{@code payment.chargeback_won} → {@code payment_risk}: not fraud (the merchant proved the payment);</li>
 *   <li>{@code order.returned_to_sender} on cash on delivery → {@code rto_risk}: confirmed refused delivery;</li>
 *   <li>{@code order.delivered} (once every group arrived) → {@code rto_risk}: not refused, a final label; and
 *       {@code payment_risk}: a <i>provisional</i> "not fraud" (a chargeback can still arrive and replace it within the
 *       chargeback window, and until then learners do not count it, see {@link FraudLabels});</li>
 *   <li>return inspection and disposition → the {@code return_risk} decision of that RMA (subject {@code return}): fraud
 *       when the grading says so ({@code fraudConfirmed}, or the item came back as the {@code wrong_item} or
 *       {@code not_as_described}); not fraud when it came back {@code new} or {@code like_new};</li>
 *   <li>a person answering the escalation task of a fraud decision ({@code task.completed}, type
 *       {@code decision_escalation}) → that decision's records: "risky" is a confirmed fraud label, "not risky" a
 *       provisional "not fraud" (a reviewer can be fooled; a chargeback still overrides it).</li>
 * </ul>
 * A confirmed label is never downgraded by a later provisional one.
 */
@Component
public class FraudLabelCapture implements EventSubscriber {
    private static final Logger log = LoggerFactory.getLogger(FraudLabelCapture.class);
    static final String LABEL = FraudLabels.LABEL;
    static final Set<String> FRAUD_DECISIONS = Set.of("payment_risk", "rto_risk", "return_risk", "address_risk");
    private static final String ESCALATION_TASK = "decision_escalation";
    private static final Set<String> ACCEPT = Set.of("accept", "accepted", "approved", "approve", "agree", "confirm", "confirmed", "ok", "yes");
    private static final Set<String> RISKY = Set.of("true", "risky", "fraud", "fraudulent", "high_risk", "confirm_fraud", "block", "hold");
    private static final Set<String> NOT_RISKY = Set.of("false", "no", "not_risky", "clear", "cleared", "release", "legitimate", "genuine", "low_risk");

    private final DecisionRecordRepository decisionRecords;
    private final HumanTaskService tasks;
    private final DecisionEscalationRepository escalations;

    public FraudLabelCapture(DecisionRecordRepository decisionRecords, HumanTaskService tasks, DecisionEscalationRepository escalations) {
        this.decisionRecords = decisionRecords;
        this.tasks = tasks;
        this.escalations = escalations;
    }

    @Override public List<String> eventTypePrefixes() {
        return List.of("payment.chargeback", "payment.chargeback_won", "order.returned_to_sender", "order.delivered", "event.delivered",
            "return.inspected", "return.dispositioned", "oms.returns.disposition", "task.completed");
    }

    @Override
    public void on(Event e) {
        var previous = TenantContext.current();
        if (e.tenantId() != null) TenantContext.set(e.tenantId());
        try {
            Map<String, Object> p = e.payload() == null ? Map.of() : e.payload();
            if ("task.completed".equals(e.type())) { onReview(e, p); return; }
            if (e.type().startsWith("return.") || e.type().startsWith("oms.returns.")) { onReturnGraded(e, p); return; }
            String orderId = orderIdOf(e);
            if (orderId == null) return;
            switch (e.type()) {
                case "payment.chargeback" -> label(orderId, "payment_risk", Boolean.TRUE.equals(p.get("fraud")),
                    Boolean.TRUE.equals(p.get("fraud")) ? "chargeback_fraud" : "chargeback_dispute", p.get("reasonCode"), e, false, true, false);
                case "payment.chargeback_won" -> label(orderId, "payment_risk", false, "chargeback_won", p.get("reasonCode"), e, true, false, false);
                case "order.returned_to_sender" -> { if (Boolean.TRUE.equals(p.get("cod"))) label(orderId, "rto_risk", true, "refused_delivery", null, e, false, false, false); }
                case "order.delivered", "event.delivered" -> {
                    if (Boolean.FALSE.equals(p.get("allDelivered"))) return; // judged once the whole order arrived
                    label(orderId, "rto_risk", false, "delivered", null, e, false, false, false);
                    label(orderId, "payment_risk", false, "delivered_no_chargeback", null, e, false, false, true);
                }
                default -> { }
            }
        } catch (RuntimeException ex) {
            log.warn("Fraud label capture failed for event {} ({}): {}", e.id(), e.type(), ex.toString());
        } finally {
            previous.ifPresentOrElse(TenantContext::set, TenantContext::clear);
        }
    }

    /**
     * FD-06: the grading of a return labels the {@code return_risk} decision made for that return (subject {@code return},
     * the RMA id). Records written against the order (older data, imports) are labelled when the RMA has none.
     */
    private void onReturnGraded(Event e, Map<String, Object> p) {
        Boolean fraud = returnGrade(p);
        if (fraud == null) return;
        Object reason = p.get("condition") != null ? p.get("condition") : p.get("reasonCode");
        String rmaId = p.get("rmaId") != null ? String.valueOf(p.get("rmaId")) : "return".equals(e.aggregateType()) ? e.aggregateId() : null;
        List<DecisionRecord> forReturn = rmaId == null ? List.of()
            : decisionRecords.forSubject("return", rmaId).stream().filter(r -> "return_risk".equals(r.decision())).toList();
        if (!forReturn.isEmpty()) {
            for (DecisionRecord r : forReturn) labelRecord(r, fraud, "return_grading", reason, e, false, false, false);
            return;
        }
        String orderId = orderIdOf(e);
        if (orderId != null) label(orderId, "return_risk", fraud, "return_grading", reason, e, false, false, false);
    }

    /**
     * Return grading: fraud when inspection says so, the wrong item came back or the item is not what the buyer described;
     * not fraud when it came back as sold. Damaged or incomplete goods are not graded either way (wear, transit damage
     * and parts harvesting look alike at the dock) unless the inspector flagged fraud.
     */
    static Boolean returnGrade(Map<String, Object> p) {
        if (Boolean.TRUE.equals(p.get("fraudConfirmed"))) return true;
        String condition = p.get("condition") == null ? "" : String.valueOf(p.get("condition")).toLowerCase(Locale.ROOT);
        return switch (condition) {
            case "wrong_item", "not_as_described" -> true;
            case "new", "like_new" -> false;
            case "" -> Boolean.FALSE.equals(p.get("fraudConfirmed")) ? Boolean.FALSE : null;
            default -> null;
        };
    }

    /** FD-06: a person's answer to a fraud decision's escalation task. */
    @SuppressWarnings("unchecked")
    private void onReview(Event e, Map<String, Object> p) {
        if (!ESCALATION_TASK.equals(p.get("type")) || p.get("taskId") == null) return;
        // E3-N1 / FD-06: a reviewer's verdict is a human label only when a person gave it; an agent's task_complete is not.
        if (!ai.intentoms.learning.model.HumanResolution.PERSON.equals(ai.intentoms.learning.model.HumanResolution.resolverType(e.actor() == null ? null : e.actor().ref()))) return;
        HumanTask task = tasks.get(String.valueOf(p.get("taskId")));
        Map<String, Object> tp = task.payload() == null ? Map.of() : task.payload();
        String decision = tp.get("decision") == null ? null : String.valueOf(tp.get("decision"));
        if (decision == null || !FRAUD_DECISIONS.contains(decision)) return;
        Boolean fraud = reviewAnswer(p, tp);
        if (fraud == null) return;
        Set<String> recordIds = new LinkedHashSet<>();
        Map<String, Object> details = p.get("details") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Object anchor = details.getOrDefault("recordId", tp.get("recordId"));
        if (anchor != null) recordIds.add(String.valueOf(anchor));
        escalations.forTask(task.id()).forEach(esc -> recordIds.add(esc.recordId()));
        for (String id : recordIds) {
            decisionRecords.find(id).filter(r -> decision.equals(r.decision()))
                .ifPresent(r -> labelRecord(r, fraud, "human_review", p.get("reason"), e, false, false, !fraud));
        }
    }

    /** The reviewer's answer: {@code details.value}, else an accepting outcome keeps the recommendation, else the outcome. */
    @SuppressWarnings("unchecked")
    static Boolean reviewAnswer(Map<String, Object> eventPayload, Map<String, Object> taskPayload) {
        Map<String, Object> details = eventPayload.get("details") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Object value;
        if (details.containsKey("value")) value = details.get("value");
        else {
            String outcome = eventPayload.get("outcome") == null ? "" : String.valueOf(eventPayload.get("outcome")).toLowerCase(Locale.ROOT);
            value = ACCEPT.contains(outcome) ? details.getOrDefault("recommendedValue", taskPayload.get("recommendedValue")) : outcome;
        }
        if (value instanceof Boolean b) return b;
        String v = value == null ? "" : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if (RISKY.contains(v)) return true;
        if (NOT_RISKY.contains(v)) return false;
        return null;
    }

    /**
     * @param overrideConfirmed write even over a confirmed label (a won chargeback is proof the payment was genuine)
     * @param upgradeOnly       a non-confirming event (a non-fraud dispute) never replaces an existing label
     * @param provisional       the label is a guess until the chargeback window has passed
     */
    private void label(String orderId, String decision, boolean fraud, String kind, Object reasonCode, Event e, boolean overrideConfirmed, boolean upgradeOnly, boolean provisional) {
        for (DecisionRecord r : decisionRecords.forSubject("order", orderId)) {
            if (decision.equals(r.decision())) labelRecord(r, fraud, kind, reasonCode, e, overrideConfirmed, upgradeOnly, provisional);
        }
    }

    private void labelRecord(DecisionRecord r, boolean fraud, String kind, Object reasonCode, Event e, boolean overrideConfirmed, boolean upgradeOnly, boolean provisional) {
        Optional<Map<String, Object>> existing = existing(r);
        if (existing.isPresent() && !fraud) {
            boolean existingFraud = Boolean.TRUE.equals(existing.get().get("confirmed"));
            if (existingFraud && !overrideConfirmed) return;
            if (!overrideConfirmed && (upgradeOnly || provisional)) return;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("confirmed", fraud);
        details.put("kind", kind);
        details.put("reasonCode", reasonCode == null ? "" : String.valueOf(reasonCode));
        details.put("sourceEvent", e.type());
        details.put("provisional", provisional);
        details.put("at", e.occurredAt() == null ? null : e.occurredAt().toString());
        if (e.actor() != null && "human_review".equals(kind)) details.put("reviewedBy", e.actor().ref());
        decisionRecords.label(r.id(), LABEL, details);
    }

    @SuppressWarnings("unchecked")
    private static Optional<Map<String, Object>> existing(DecisionRecord r) {
        Object o = r.labels() == null ? null : r.labels().get(LABEL);
        return o instanceof Map<?, ?> m && ((Map<String, Object>) m).get("confirmed") instanceof Boolean ? Optional.of((Map<String, Object>) m) : Optional.empty();
    }

    private static String orderIdOf(Event e) {
        if (e.payload() != null) {
            Object o = e.payload().getOrDefault("orderId", e.payload().get("order_id"));
            if (o != null) return String.valueOf(o);
        }
        if (e.metadata() != null && e.metadata().get("orderId") != null) return String.valueOf(e.metadata().get("orderId"));
        return "order".equals(e.aggregateType()) ? e.aggregateId() : e.correlationId();
    }
}
