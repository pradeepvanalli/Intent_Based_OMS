package ai.intentoms.finance.guard;

import java.math.BigDecimal;

/**
 * Pure outcome of comparing a proposed spend against a cap (GR-05); the arithmetic {@link SpendGuard} relies on,
 * kept side-effect free so it can be unit-tested exhaustively.
 *
 * @param scope        which cap was evaluated: tenant | actor | agent
 * @param cap          the configured cap for the rolling window
 * @param spent        spend already recorded in the window
 * @param amount       the proposed spend
 * @param withinCap    {@code spent + amount <= cap}
 * @param tripsBreaker {@code spent > cap × breakerFactor}; only meaningful for the tenant scope
 */
public record SpendVerdict(String scope, BigDecimal cap, BigDecimal spent, BigDecimal amount, boolean withinCap, boolean tripsBreaker) {

    /** Factor over the tenant cap at which the circuit breaker opens (cap × 1.5). */
    public static final BigDecimal BREAKER_FACTOR = new BigDecimal("1.5");

    public static SpendVerdict evaluate(String scope, BigDecimal cap, BigDecimal spent, BigDecimal amount) {
        boolean within = spent.add(amount).compareTo(cap) <= 0;
        boolean trips = spent.compareTo(breakerThreshold(cap)) > 0;
        return new SpendVerdict(scope, cap, spent, amount, within, trips);
    }

    /** Threshold above which recorded rolling-window spend opens the breaker. */
    public static BigDecimal breakerThreshold(BigDecimal cap) { return cap.multiply(BREAKER_FACTOR); }

    /** Headroom left under the cap before this spend (never negative). */
    public BigDecimal remaining() { return cap.subtract(spent).max(BigDecimal.ZERO); }
}
