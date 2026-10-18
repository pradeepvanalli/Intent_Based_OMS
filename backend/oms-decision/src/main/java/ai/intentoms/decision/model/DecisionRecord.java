package ai.intentoms.decision.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Persisted evaluation (table decision_record, FR-DEC-06) including override and label columns (GR-13, FR-DEC-10).
 * contextFeatures/arms/chosenArm/propensity/policyVersion are the SOO propensity-logging fields (requirements doc
 * OC-01): without propensity, a past decision can't be fairly replayed against a new policy. propensity defaults to
 * 1.0 for a deterministic choice (L0 rules, a single-candidate L1 call); a learner that samples among several arms
 * (the FS-01 sourcing-policy bandit, for example) must pass the actual sampling probability of the chosen arm.
 */
public record DecisionRecord(String id, String decision, int version, Mode mode, String subjectType, String subjectId, String correlationId,
                             Layer layer, Band band, String action, Object value, Map<String, Answer> answers, double confidence, String provider, String modelVersion,
                             String inputHash, Map<String, Object> state, Map<String, Object> input, List<String> rulesFired, List<String> reasonCodes,
                             long latencyMs, double costUsd, int inputTokens, boolean requiresHuman, String escalationId, String actor, Map<String, Object> details,
                             Object overriddenValue, String overrideReason, String overriddenBy, Instant overriddenAt, Map<String, Object> labels, Instant createdAt,
                             Map<String, Object> contextFeatures, List<String> arms, String chosenArm, double propensity, Integer policyVersion) {
    /** Effective value after any human override (GR-13). */
    public Object effectiveValue() { return overriddenAt != null ? overriddenValue : value; }
}
