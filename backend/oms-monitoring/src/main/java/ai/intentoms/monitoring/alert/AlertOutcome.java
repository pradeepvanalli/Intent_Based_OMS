package ai.intentoms.monitoring.alert;

import java.util.List;

/** Result of {@link AlertService#alert}: the alert row touched, whether it was suppressed as a duplicate, channels that accepted it and channels that failed. */
public record AlertOutcome(String alertId, boolean deduplicated, List<String> channelsSent, List<String> failures) {
    public boolean sent() { return !channelsSent.isEmpty(); }
}
