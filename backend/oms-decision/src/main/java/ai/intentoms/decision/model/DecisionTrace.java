package ai.intentoms.decision.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Full "why" of a decision (FR-XAI-01): inputs passed (tokenised), questions with criteria, answers with probabilities
 * and confidence, rules fired, check results, band thresholds, action, layer, provider, timings, cost, actor, escalation,
 * override and labels. {@code learning} (XP-03) is what the self-optimizing layer contributed: the learned facts and
 * their versions, raw vs calibrated confidence, experiment arm, provider routing and the logged arms/propensity.
 */
public record DecisionTrace(String recordId, String decision, int version, Tier tier, Mode mode, Map<String, Object> definition, Map<String, Object> state,
                            List<QuestionTrace> questions, Map<String, Answer> answers, List<String> rulesFired, List<CheckResult> preChecks, List<CheckResult> postChecks,
                            Bands bands, Band band, String action, Layer layer, Object value, double confidence, boolean requiresHuman, List<String> reasonCodes,
                            String provider, String modelVersion, long latencyMs, double costUsd, String actor, DecisionEscalation escalation,
                            Map<String, Object> override, Map<String, Object> labels, Map<String, Object> details, Instant createdAt,
                            Map<String, Object> learning) {

    /** One question as asked, with its criteria and the answer received. */
    public record QuestionTrace(String key, String type, String instructions, Map<String, String> choiceCriteria, List<String> scoreLevels, boolean reasonCode, Answer answer) {}
}
