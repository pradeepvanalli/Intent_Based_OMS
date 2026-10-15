package ai.intentoms.monitoring.playbook;

import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.monitoring.exception.ExceptionRecord;
import ai.intentoms.order.model.Order;
import ai.intentoms.platform.ledger.Actor;

import java.util.Map;
import java.util.Optional;

/**
 * What a {@link PlaybookAction} sees (FR-MON-04): the exception, the order when known, the classification decision
 * (null for exceptions raised with a fixed type, e.g. {@code sla_breach}), the tenant's spend limits
 * ({@code reship_usd, refund_usd, appeasement_usd}) and the actor steps should attribute their writes to.
 */
public record PlaybookContext(ExceptionRecord exception, Optional<Order> order, DecisionResult decisionResult, Map<String, Object> spendLimits, Actor actor) {
    public PlaybookContext {
        order = order == null ? Optional.empty() : order;
        spendLimits = spendLimits == null ? Map.of() : Map.copyOf(spendLimits);
        actor = actor == null ? Actor.system("playbook") : actor;
    }

    /** Confidence gates compare against: the classification decision, else the stored confidence, else 1.0 (type was certain). */
    public double confidence() {
        if (decisionResult != null) return decisionResult.confidence();
        return exception.classificationConfidence() == null ? 1.0 : exception.classificationConfidence();
    }

    public String orderId() { return exception.orderId(); }

    /** Spend limit for a category ({@code reship_usd} ...), empty when none configured. */
    public Optional<Double> spendLimit(String category) {
        Object v = spendLimits.get(category);
        return v instanceof Number n ? Optional.of(n.doubleValue()) : Optional.empty();
    }
}
