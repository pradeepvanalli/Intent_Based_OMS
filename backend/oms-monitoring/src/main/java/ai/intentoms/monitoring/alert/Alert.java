package ai.intentoms.monitoring.alert;

import java.util.Map;
import java.util.Set;

/**
 * An operational alert (FR-MON-06). {@code key} is the de-duplication key (repeats within {@code alerts.dedupe_minutes}
 * are counted, not re-sent); {@code severity} is {@code low|medium|high|critical}; {@code channels} restricts delivery to
 * the named channels ({@code slack, teams, email, pagerduty}), empty meaning every configured one.
 */
public record Alert(String key, String severity, String title, String body, Map<String, String> links, Set<String> channels) {
    public Alert {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("alert key is required");
        severity = severity == null || severity.isBlank() ? "medium" : severity.toLowerCase();
        title = title == null ? key : title;
        body = body == null ? "" : body;
        links = links == null ? Map.of() : Map.copyOf(links);
        channels = channels == null ? Set.of() : Set.copyOf(channels);
    }

    public Alert(String key, String severity, String title, String body, Map<String, String> links) { this(key, severity, title, body, links, Set.of()); }

    public boolean critical() { return "critical".equals(severity); }
}
