package ai.intentoms.monitoring.exception;

/** One taxonomy entry (FR-MON-03): operational meaning of a classifier type — severity, owning queue, playbook and resolution SLA. */
public record ExceptionTypeDef(String type, String severity, String queue, String playbook, int slaMinutes, String description) {
    public static final String OTHER = "other";
    public static final String UNCLEAR = "unclear";

    public ExceptionTypeDef {
        if (type == null || type.isBlank()) throw new IllegalArgumentException("type is required");
        severity = severity == null || severity.isBlank() ? "medium" : severity.toLowerCase();
        queue = queue == null || queue.isBlank() ? "unclassified" : queue;
        if (slaMinutes <= 0) slaMinutes = 240;
    }

    /** Task priority from severity: critical 100, high 75, medium 50, low 25. */
    public int priority() {
        return switch (severity) { case "critical" -> 100; case "high" -> 75; case "medium" -> 50; default -> 25; };
    }
}
