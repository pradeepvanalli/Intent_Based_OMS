package ai.intentoms.sourcing.node;

import java.time.Duration;
import java.time.Instant;

/** Learned-penalty decay (FR-SRC-07): {@code effective = penalty × 0.5^(days since update / 7)}. */
public final class PenaltyDecay {
    public static final double HALF_LIFE_DAYS = 7.0;

    private PenaltyDecay() {}

    public static double effective(double penalty, Instant updatedAt, Instant now) {
        if (penalty <= 0 || updatedAt == null || now == null) return Math.max(0, penalty);
        double days = Math.max(0, Duration.between(updatedAt, now).toMillis() / 86_400_000.0);
        return penalty * Math.pow(0.5, days / HALF_LIFE_DAYS);
    }

    public static double clamp(double p) { return Math.max(0.0, Math.min(1.0, p)); }
}
