package ai.intentoms.decision.model;

import java.util.List;
import java.util.Map;

/**
 * Outcome of one evaluation (FR-DEC-06). {@code action} is act | confirm | escalate | blocked | default. When
 * {@code requiresHuman} is true the value is a recommendation only (T3, escalations).
 */
public record DecisionResult(String recordId, String decision, int version, Layer layer, Band band, Object value, Map<String, Answer> answers, double confidence,
                             String action, boolean requiresHuman, String escalationId, List<String> rulesFired, List<String> reasonCodes, long latencyMs, double costUsd,
                             String provider, String modelVersion, Map<String, Object> details) {
    public static final String ACT = "act";
    public static final String CONFIRM = "confirm";
    public static final String ESCALATE = "escalate";
    public static final String BLOCKED = "blocked";
    public static final String DEFAULT = "default";

    public DecisionResult {
        answers = answers == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(answers));
        rulesFired = rulesFired == null ? List.of() : List.copyOf(rulesFired);
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        details = details == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(details));
    }

    /** True when the system may act on the value autonomously. */
    public boolean acts() { return ACT.equals(action) && !requiresHuman; }
}
