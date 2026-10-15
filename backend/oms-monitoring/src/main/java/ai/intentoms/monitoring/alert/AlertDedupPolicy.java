package ai.intentoms.monitoring.alert;

import java.time.Duration;
import java.time.Instant;

/** De-duplication rule (FR-MON-06): an alert with the same key is suppressed while the previous occurrence is younger than the window. */
public final class AlertDedupPolicy {
    public static final int DEFAULT_WINDOW_MINUTES = 15;
    public static final String WINDOW_KEY = "alerts.dedupe_minutes";

    private AlertDedupPolicy() {}

    public static boolean suppress(Instant lastSeen, Instant now, Duration window) {
        if (lastSeen == null || window == null || window.isZero() || window.isNegative()) return false;
        return !lastSeen.plus(window).isBefore(now);
    }
}
