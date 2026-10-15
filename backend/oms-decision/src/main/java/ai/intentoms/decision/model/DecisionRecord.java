package ai.intentoms.decision.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Persisted evaluation (table decision_record, FR-DEC-06) including override and label columns (GR-13, FR-DEC-10). */
public record DecisionRecord(String id, String decision, int version, Mode mode, String subjectType, String subjectId, String correlationId,
                             Layer layer, Band band, String action, Object value, Map<String, Answer> answers, double confidence, String provider, String modelVersion,
                             String inputHash, Map<String, Object> state, Map<String, Object> input, List<String> rulesFired, List<String> reasonCodes,
                             long latencyMs, double costUsd, int inputTokens, boolean requiresHuman, String escalationId, String actor, Map<String, Object> details,
                             Object overriddenValue, String overrideReason, String overriddenBy, Instant overriddenAt, Map<String, Object> labels, Instant createdAt) {
    /** Effective value after any human override (GR-13). */
    public Object effectiveValue() { return overriddenAt != null ? overriddenValue : value; }
}
