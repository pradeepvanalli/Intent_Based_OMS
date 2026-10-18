package ai.intentoms.learning.learner;

import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.ledger.EventSubscriber;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * FD-06: labels the fraud-adjacent decisions for an order ({@code payment_risk}, {@code return_risk},
 * {@code rto_risk}) with what actually happened — a chargeback, a confirmed-fraud return grading, or a refund
 * failure — so {@code FraudThresholdTuner} (FD-03) can compute a real false-positive/false-negative rate against
 * ground truth. Label delay is intentionally not re-implemented here: a label applies whenever the triggering
 * event lands, and {@code RewardMaturityService}'s existing 120-day window already governs when an episode counts
 * the outcome as final (OC-04); this only needs the decision_record label to exist by then.
 */
@Component
public class FraudLabelCapture implements EventSubscriber {
    private static final Logger log = LoggerFactory.getLogger(FraudLabelCapture.class);
    private static final Set<String> FRAUD_DECISIONS = Set.of("payment_risk", "return_risk", "rto_risk");

    private final DecisionRecordRepository decisionRecords;
    public FraudLabelCapture(DecisionRecordRepository decisionRecords) { this.decisionRecords = decisionRecords; }

    @Override public List<String> eventTypePrefixes() { return List.of("refund.failed", "payment.failed", "return.dispositioned", "oms.returns.disposition"); }

    @Override
    public void on(Event e) {
        if (e.tenantId() != null) TenantContext.set(e.tenantId());
        try {
            String orderId = orderIdOf(e);
            if (orderId == null) return;
            boolean fraudConfirmed = isFraudSignal(e);
            String reasonCode = switch (e.type()) {
                case "refund.failed", "payment.failed" -> "payment_failed";
                default -> e.payload() == null ? null : String.valueOf(e.payload().getOrDefault("reasonCode", ""));
            };
            for (DecisionRecord r : decisionRecords.forSubject("order", orderId)) {
                if (!FRAUD_DECISIONS.contains(r.decision())) continue;
                decisionRecords.label(r.id(), "fraud_outcome", Map.of("confirmed", fraudConfirmed, "reasonCode", reasonCode == null ? "" : reasonCode, "sourceEvent", e.type()));
            }
        } catch (RuntimeException ex) {
            log.warn("Fraud label capture failed for event {} ({}): {}", e.id(), e.type(), ex.toString());
        } finally {
            TenantContext.clear();
        }
    }

    private static boolean isFraudSignal(Event e) {
        if ("payment.failed".equals(e.type()) || "refund.failed".equals(e.type())) return true;
        Object fraud = e.payload() == null ? null : e.payload().get("fraudConfirmed");
        return Boolean.TRUE.equals(fraud);
    }

    private static String orderIdOf(Event e) {
        if (e.payload() != null) {
            Object o = e.payload().getOrDefault("orderId", e.payload().get("order_id"));
            if (o != null) return String.valueOf(o);
        }
        return "order".equals(e.aggregateType()) ? e.aggregateId() : e.correlationId();
    }
}
