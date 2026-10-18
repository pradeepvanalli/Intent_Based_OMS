package ai.intentoms.orchestration.fraud;

import ai.intentoms.orchestration.escalation.HumanTaskEscalationHandler;
import ai.intentoms.orchestration.workflow.OrderWorkflowSupport;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.ledger.EventSubscriber;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.statemachine.model.Hold;
import ai.intentoms.workflow.task.HumanTask;
import ai.intentoms.workflow.task.HumanTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Closes the loop on a payment or refused-delivery review (FD-01/FD-02): when a person answers the escalation task of
 * a {@code payment_risk} or {@code rto_risk} decision with "not risky", the order's {@code payment_review} or
 * {@code address_confirmation} hold is released, which resumes the workflow ({@code hold.released} signals it). When
 * they confirm the risk, the hold stays and a payment exception is raised so someone decides what to do with the
 * order; cancelling is never automatic. The answer is read the same way as {@code DecisionEscalationTaskSubscriber}:
 * {@code details.value}, else an accepting outcome keeps the recommendation, else the outcome itself is the value.
 */
@Component
public class PaymentReviewTaskSubscriber implements EventSubscriber {
    private static final Logger log = LoggerFactory.getLogger(PaymentReviewTaskSubscriber.class);
    private static final Set<String> ACCEPT = Set.of("accept", "accepted", "approved", "approve", "agree", "confirm", "confirmed", "ok", "yes");
    private static final Set<String> NOT_RISKY = Set.of("false", "no", "not_risky", "clear", "cleared", "release", "legitimate", "genuine");

    private final HumanTaskService tasks;
    private final OrderWorkflowSupport s;

    public PaymentReviewTaskSubscriber(HumanTaskService tasks, OrderWorkflowSupport s) {
        this.tasks = tasks;
        this.s = s;
    }

    @Override public List<String> eventTypePrefixes() { return List.of("task.completed"); }

    @Override
    public void on(Event e) {
        if (e.payload() == null || !HumanTaskEscalationHandler.TASK_TYPE.equals(e.payload().get("type"))) return;
        var previous = TenantContext.current();
        if (e.tenantId() != null) TenantContext.set(e.tenantId());
        try {
            HumanTask task = tasks.get(String.valueOf(e.payload().get("taskId")));
            Map<String, Object> p = task.payload() == null ? Map.of() : task.payload();
            String decision = String.valueOf(p.get("decision"));
            String holdType = PaymentRiskGate.PAYMENT_DECISION.equals(decision) ? PaymentRiskGate.PAYMENT_HOLD
                : PaymentRiskGate.RTO_DECISION.equals(decision) ? PaymentRiskGate.ADDRESS_HOLD : null;
            if (holdType == null || !"order".equals(task.subjectType()) || task.subjectId() == null) return;
            Object value = answer(e.payload(), p);
            String orderId = task.subjectId();
            String by = e.actor() == null ? "system:task" : e.actor().ref();
            if (notRisky(value)) {
                for (Hold h : s.holdsOfType(orderId, holdType)) s.holds().release(h.id(), "cleared by review (" + by + ")", Actor.system("payment-review"));
                log.info("{} review for order {} cleared by {}; {} hold released", decision, orderId, by, holdType);
            } else if (PaymentRiskGate.PAYMENT_DECISION.equals(decision)) {
                s.raise("payment", "payment_fraud_confirmed", "A reviewer confirmed the payment-fraud risk; the order stays held", orderId, null, null,
                    Map.of("taskId", task.id(), "reviewer", by), "payment", "");
            }
        } catch (RuntimeException ex) {
            log.warn("Payment review follow-up failed for task event {}: {}", e.id(), ex.toString());
        } finally {
            previous.ifPresentOrElse(TenantContext::set, TenantContext::clear);
        }
    }

    @SuppressWarnings("unchecked")
    static Object answer(Map<String, Object> eventPayload, Map<String, Object> taskPayload) {
        Map<String, Object> details = eventPayload.get("details") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        if (details.containsKey("value")) return details.get("value");
        String outcome = eventPayload.get("outcome") == null ? "" : String.valueOf(eventPayload.get("outcome"));
        return ACCEPT.contains(outcome.toLowerCase(Locale.ROOT)) ? details.getOrDefault("recommendedValue", taskPayload.get("recommendedValue")) : outcome;
    }

    static boolean notRisky(Object value) {
        if (value instanceof Boolean b) return !b;
        return value != null && NOT_RISKY.contains(String.valueOf(value).trim().toLowerCase(Locale.ROOT));
    }
}
