package ai.intentoms.workflow.model;

import java.time.Duration;

/**
 * Retry policy for an activity (FR-WF-02). {@code maxAttempts} counts executions including the first; backoff grows
 * geometrically from {@code initialBackoff} by {@code multiplier}, capped at {@code maxBackoff}.
 */
public record RetryPolicy(int maxAttempts, Duration initialBackoff, double multiplier, Duration maxBackoff) {
    public RetryPolicy {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
        if (initialBackoff == null || initialBackoff.isNegative()) throw new IllegalArgumentException("initialBackoff must be >= 0");
        if (multiplier < 1.0) throw new IllegalArgumentException("multiplier must be >= 1");
        if (maxBackoff == null || maxBackoff.compareTo(initialBackoff) < 0) maxBackoff = initialBackoff;
    }

    /** Five attempts: 1 s, 2 s, 4 s, 8 s, capped at 1 min. */
    public static RetryPolicy standard() { return new RetryPolicy(5, Duration.ofSeconds(1), 2.0, Duration.ofMinutes(1)); }

    /** Exactly one attempt; a failure fails the step. */
    public static RetryPolicy none() { return new RetryPolicy(1, Duration.ZERO, 1.0, Duration.ZERO); }

    public static RetryPolicy of(int maxAttempts, Duration initialBackoff) { return new RetryPolicy(maxAttempts, initialBackoff, 2.0, Duration.ofMinutes(5)); }

    /** Backoff to wait after the given failed attempt (1-based). */
    public Duration backoffAfter(int failedAttempt) {
        double millis = initialBackoff.toMillis() * Math.pow(multiplier, Math.max(0, failedAttempt - 1));
        long capped = (long) Math.min(millis, (double) maxBackoff.toMillis());
        return Duration.ofMillis(Math.max(0, capped));
    }
}
