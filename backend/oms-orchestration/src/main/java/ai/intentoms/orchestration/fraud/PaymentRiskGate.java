package ai.intentoms.orchestration.fraud;

import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.model.Layer;
import ai.intentoms.decision.model.Mode;
import ai.intentoms.decision.runtime.DecisionService;
import ai.intentoms.orchestration.integration.CustomerNotifier;
import ai.intentoms.orchestration.workflow.OrderWorkflowSupport;
import ai.intentoms.order.model.Order;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.statemachine.model.Hold;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pre-capture fraud checks in the order workflow, before tenders are authorised.
 *
 * <p>FD-01 {@code payment_risk} runs for every order. Its L0 rule clears orders with no risk signal, so a normal order
 * never reaches a model or a person. When the decision says high risk with confidence, or hands the call to a person
 * (T2: blocking a good order is costly), a {@code payment_review} hold blocks release and shipment, which is when the
 * payment would be captured. The order keeps moving up to that point. A person clears it by releasing the hold or
 * answering the escalation task (see {@link PaymentReviewTaskSubscriber}).
 *
 * <p>FD-02 {@code rto_risk} runs for cash-on-delivery orders. When the order looks likely to be refused at the door, the
 * customer is offered a discount to pay now and asked to confirm the address. An {@code address_confirmation} hold
 * pauses release until they confirm or the hold lapses ({@code fraud.rto.confirmation_hold_hours}, default 12).
 * Nothing here ever cancels an order.
 *
 * <p>Model failure never blocks an order: a default-layer result, or an exception from the decision, leaves the order
 * untouched (GR-07). Tenant switches: {@code fraud.payment_risk.enabled}, {@code fraud.rto_risk.enabled} (default true).
 */
@Component
public class PaymentRiskGate {
    private static final Logger log = LoggerFactory.getLogger(PaymentRiskGate.class);
    public static final String PAYMENT_DECISION = "payment_risk";
    public static final String RTO_DECISION = "rto_risk";
    public static final String PAYMENT_HOLD = "payment_review";
    public static final String ADDRESS_HOLD = "address_confirmation";
    /** Same scope as the agent-verification hold: blocks release to the warehouse and shipment (capture happens there). */
    public static final Set<String> HOLD_BLOCKS = Set.of("decision.sourcing_plan", "release", "event.shipped");

    private final DecisionService decisions;
    private final PaymentRiskFeatures features;
    private final OrderWorkflowSupport s;
    private final CustomerNotifier notifier;
    private final TenantConfigStore config;
    private final Metrics metrics;

    public PaymentRiskGate(DecisionService decisions, PaymentRiskFeatures features, OrderWorkflowSupport s, CustomerNotifier notifier, TenantConfigStore config, Metrics metrics) {
        this.decisions = decisions;
        this.features = features;
        this.s = s;
        this.notifier = notifier;
        this.config = config;
        this.metrics = metrics;
    }

    public Map<String, Object> assess(Order order) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(PAYMENT_DECISION, config.getBoolean("fraud.payment_risk.enabled", true) ? paymentRisk(order) : Map.of("status", "disabled"));
        if (PaymentRiskFeatures.hasCod(order)) out.put(RTO_DECISION, config.getBoolean("fraud.rto_risk.enabled", true) ? rtoRisk(order) : Map.of("status", "disabled"));
        return out;
    }

    Map<String, Object> paymentRisk(Order order) {
        Map<String, Object> summary = decide(PAYMENT_DECISION, features.paymentRisk(order), order);
        if (!Boolean.TRUE.equals(summary.get("flagged"))) return summary;
        String hold = ensureHold(order.id(), PAYMENT_HOLD, "payment_risk", "Payment held for fraud review before capture", null);
        summary.put("holdId", hold);
        if (!Boolean.TRUE.equals(summary.get("requiresHuman"))) {
            // A confident "high risk" has no escalation task behind it: raise an exception so a person sees it.
            Map<String, Object> exc = s.raise("payment", "payment_risk_hold", "High payment-fraud risk; held before capture", order.id(), null, null,
                Map.of("decisionRecordId", String.valueOf(summary.get("recordId")), "holdId", hold), "payment", "");
            summary.put("exceptionId", exc.get("exceptionId"));
        }
        metrics.count("oms.fraud.payment_review_hold", "reason", Boolean.TRUE.equals(summary.get("requiresHuman")) ? "escalated" : "high_risk");
        return summary;
    }

    Map<String, Object> rtoRisk(Order order) {
        Map<String, Object> summary = decide(RTO_DECISION, features.rtoRisk(order), order);
        if (!Boolean.TRUE.equals(summary.get("flagged"))) return summary;
        double discount = config.getDouble("fraud.rto.prepaid_discount_pct", 5.0);
        long hours = config.getInt("fraud.rto.confirmation_hold_hours", 12);
        String hold = ensureHold(order.id(), ADDRESS_HOLD, "rto_risk", "Waiting for the customer to confirm the delivery address (cash on delivery)", Duration.ofHours(hours));
        // One message carrying both actions (confirm the address, or pay now for a discount): two SMS for one order
        // read as spam and doubled the contact cost.
        String message = notifier.notify(order.id(), "sms", "cod_confirm_or_prepay", "Confirm your delivery address",
            "Please confirm the delivery address for order " + order.channelOrderId() + " so we can ship it, or pay online now and save "
                + fmt(discount) + "% (your order ships as soon as payment is confirmed).",
            Map.of("discountPct", discount, "orderRef", String.valueOf(order.channelOrderId())));
        Map<String, Object> evt = new LinkedHashMap<>();
        evt.put("orderId", order.id());
        evt.put("decisionRecordId", summary.get("recordId"));
        evt.put("discountPct", discount);
        evt.put("holdId", hold);
        evt.put("holdHours", hours);
        s.appendOrderEvent(order.id(), "order.cod_prepaid_offered", evt);
        s.appendOrderEvent(order.id(), "order.address_confirmation_requested", evt);
        summary.put("holdId", hold);
        summary.put("actions", List.of("prepaid_conversion_offer", "address_confirmation"));
        summary.put("communications", List.of(String.valueOf(message)));
        metrics.count("oms.fraud.rto_actions");
        return summary;
    }

    private Map<String, Object> decide(String decision, Map<String, Object> input, Order order) {
        Map<String, Object> summary = new LinkedHashMap<>();
        DecisionResult r;
        try {
            Map<String, Object> extra = order.customerRef() == null ? Map.of() : Map.of("customerRef", order.customerRef());
            r = decisions.decide(decision, input, new DecisionOptions("order", order.id(), order.id(), Mode.LIVE, null, OrderWorkflowSupport.ACTOR, extra));
        } catch (RuntimeException e) {
            log.warn("{} unavailable for order {}; continuing without it: {}", decision, order.id(), e.toString());
            metrics.count("oms.fraud.decision_unavailable", "decision", decision);
            summary.put("status", "unavailable");
            summary.put("flagged", false);
            return summary;
        }
        boolean degraded = r.layer() == Layer.DEFAULT;
        boolean highRisk = DecisionResult.ACT.equals(r.action()) && Boolean.TRUE.equals(r.value());
        summary.put("recordId", r.recordId());
        summary.put("layer", r.layer().name());
        summary.put("band", r.band() == null ? null : r.band().name());
        summary.put("action", r.action());
        summary.put("value", r.value());
        summary.put("confidence", r.confidence());
        summary.put("requiresHuman", r.requiresHuman());
        summary.put("flagged", !degraded && (highRisk || r.requiresHuman()));
        if (!r.rulesFired().isEmpty()) summary.put("rulesFired", r.rulesFired());
        return summary;
    }

    /** One active hold of a type per order; re-running the check (activity retry) never stacks holds. */
    private String ensureHold(String orderId, String type, String reasonCode, String note, Duration ttl) {
        List<Hold> existing = s.holdsOfType(orderId, type);
        if (!existing.isEmpty()) return existing.get(0).id();
        return s.holds().place(type, "order", orderId, HOLD_BLOCKS, reasonCode, note, ttl, OrderWorkflowSupport.ACTOR).id();
    }

    private static String fmt(double d) { return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d); }
}
