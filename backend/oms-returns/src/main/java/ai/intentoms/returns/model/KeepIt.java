package ai.intentoms.returns.model;

import java.math.BigDecimal;

/**
 * Keep-it (returnless refund) evaluation (FR-RET-05): offered when the cost of getting the item back exceeds what it
 * would recover and abuse risk is low.
 *
 * @param offered          whether the customer keeps the item and is refunded without shipping it back
 * @param reason           {@code return_cost_exceeds_recovery} when offered, otherwise why not (recovery_exceeds_cost | risk_too_high | disabled | not_evaluated)
 * @param returnCostUsd    estimated return shipping + handling
 * @param recoveryValueUsd estimated resale recovery of the returned units
 */
public record KeepIt(boolean offered, String reason, BigDecimal returnCostUsd, BigDecimal recoveryValueUsd) {
    public static KeepIt notEvaluated() { return new KeepIt(false, "not_evaluated", null, null); }
}
