package ai.intentoms.monitoring.sla;

import java.time.Duration;
import java.time.Instant;

/**
 * SLA for quantity sitting in {@code state} of {@code entity} for orders of {@code orderType} ({@code "*"} = any)
 * (FR-MON-02, FR-SM-08). Durations are ISO-8601 strings ({@code PT4H}); {@code onBreach} is {@code playbook.<name>} or
 * {@code decision.<name>}; {@code expectedNextEventWithin} drives the stuck-order sweeper (FR-MON-05).
 */
public record SlaDefinition(String id, String orderType, String entity, String state, String maxAge, String onBreach, String expectedNextEventWithin, Instant updatedAt) {
    public SlaDefinition {
        orderType = orderType == null || orderType.isBlank() ? "*" : orderType.trim();
        entity = entity == null || entity.isBlank() ? "order_line" : entity.trim();
        if (state == null || state.isBlank()) throw new IllegalArgumentException("state is required");
        maxAge = normalise(maxAge);
        expectedNextEventWithin = normalise(expectedNextEventWithin);
        onBreach = onBreach == null || onBreach.isBlank() ? null : onBreach.trim();
    }

    private static String normalise(String iso) {
        if (iso == null || iso.isBlank()) return null;
        return Duration.parse(iso.trim()).toString();   // validates and canonicalises
    }

    public Duration maxAgeDuration() { return maxAge == null ? null : Duration.parse(maxAge); }
    public Duration expectedNextEventWithinDuration() { return expectedNextEventWithin == null ? null : Duration.parse(expectedNextEventWithin); }
}
