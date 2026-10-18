package ai.intentoms.learning.learner;

import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.ledger.EventSubscriber;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FD-06 (and the FD-02 refused-delivery label): writes {@code fraud_outcome} on each fraud decision of an order with
 * what actually happened, so {@code FraudThresholdTuner} (FD-03) measures false positives and negatives against
 * ground truth. Each event labels only the decision it is evidence for:
 * <ul>
 *   <li>{@code payment.chargeback} → {@code payment_risk}: confirmed when the gateway reason is fraud (unauthorised),
 *       a non-fraud dispute is recorded but does not confirm fraud;</li>
 *   <li>{@code payment.chargeback_won} → {@code payment_risk}: not fraud (the merchant proved the payment);</li>
 *   <li>{@code order.returned_to_sender} on cash on delivery → {@code rto_risk}: confirmed refused delivery;</li>
 *   <li>{@code order.delivered} → {@code rto_risk} and {@code payment_risk}: a provisional "not fraud" label when none
 *       exists yet (a chargeback can still arrive and replace it, up to the 120-day chargeback window);</li>
 *   <li>return dispositions → {@code return_risk}, confirmed when the grading says fraud.</li>
 * </ul>
 * A confirmed label is never downgraded by a later provisional one.
 */
@Component
public class FraudLabelCapture implements EventSubscriber {
    private static final Logger log = LoggerFactory.getLogger(FraudLabelCapture.class);
    static final String LABEL = "fraud_outcome";

    private final DecisionRecordRepository decisionRecords;
    public FraudLabelCapture(DecisionRecordRepository decisionRecords) { this.decisionRecords = decisionRecords; }

    @Override public List<String> eventTypePrefixes() {
        return List.of("payment.chargeback", "payment.chargeback_won", "order.returned_to_sender", "order.delivered", "event.delivered",
            "return.dispositioned", "oms.returns.disposition");
    }

    @Override
    public void on(Event e) {
        var previous = TenantContext.current();
        if (e.tenantId() != null) TenantContext.set(e.tenantId());
        try {
            String orderId = orderIdOf(e);
            if (orderId == null) return;
            Map<String, Object> p = e.payload() == null ? Map.of() : e.payload();
            switch (e.type()) {
                case "payment.chargeback" -> label(orderId, "payment_risk", Boolean.TRUE.equals(p.get("fraud")),
                    Boolean.TRUE.equals(p.get("fraud")) ? "chargeback_fraud" : "chargeback_dispute", p.get("reasonCode"), e, false, true);
                case "payment.chargeback_won" -> label(orderId, "payment_risk", false, "chargeback_won", p.get("reasonCode"), e, true, false);
                case "order.returned_to_sender" -> { if (Boolean.TRUE.equals(p.get("cod"))) label(orderId, "rto_risk", true, "refused_delivery", null, e, false, false); }
                case "order.delivered", "event.delivered" -> {
                    label(orderId, "rto_risk", false, "delivered", null, e, false, false);
                    label(orderId, "payment_risk", false, "delivered_no_chargeback", null, e, false, false);
                }
                default -> label(orderId, "return_risk", Boolean.TRUE.equals(p.get("fraudConfirmed")), "return_disposition", p.get("reasonCode"), e, false, false);
            }
        } catch (RuntimeException ex) {
            log.warn("Fraud label capture failed for event {} ({}): {}", e.id(), e.type(), ex.toString());
        } finally {
            previous.ifPresentOrElse(TenantContext::set, TenantContext::clear);
        }
    }

    /**
     * @param overrideConfirmed write even over a confirmed label (a won chargeback is proof the payment was genuine)
     * @param upgradeOnly       a non-confirming event (a non-fraud dispute) never replaces an existing label
     */
    private void label(String orderId, String decision, boolean confirmed, String kind, Object reasonCode, Event e, boolean overrideConfirmed, boolean upgradeOnly) {
        for (DecisionRecord r : decisionRecords.forSubject("order", orderId)) {
            if (!decision.equals(r.decision())) continue;
            Boolean existing = existing(r);
            if (existing != null && !confirmed) {
                if (Boolean.TRUE.equals(existing) && !overrideConfirmed) continue;
                if (!overrideConfirmed && (upgradeOnly || isProvisional(kind))) continue;
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("confirmed", confirmed);
            details.put("kind", kind);
            details.put("reasonCode", reasonCode == null ? "" : String.valueOf(reasonCode));
            details.put("sourceEvent", e.type());
            details.put("provisional", isProvisional(kind));
            details.put("at", e.occurredAt() == null ? null : e.occurredAt().toString());
            decisionRecords.label(r.id(), LABEL, details);
        }
    }

    private static boolean isProvisional(String kind) { return kind.startsWith("delivered"); }

    @SuppressWarnings("unchecked")
    private static Boolean existing(DecisionRecord r) {
        Object o = r.labels() == null ? null : r.labels().get(LABEL);
        return o instanceof Map<?, ?> m && ((Map<String, Object>) m).get("confirmed") instanceof Boolean b ? b : null;
    }

    private static String orderIdOf(Event e) {
        if (e.payload() != null) {
            Object o = e.payload().getOrDefault("orderId", e.payload().get("order_id"));
            if (o != null) return String.valueOf(o);
        }
        return "order".equals(e.aggregateType()) ? e.aggregateId() : e.correlationId();
    }
}
