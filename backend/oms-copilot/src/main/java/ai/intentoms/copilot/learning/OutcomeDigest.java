package ai.intentoms.copilot.learning;

import java.util.Map;

/**
 * Aggregates only — the Analyst's only input (SOO requirements doc §6.1/§6.4). Never a single order, never raw
 * PII: {@code scope} names a cohort (e.g. {@code node:DC-NV} or {@code segment:split_tolerant}), not an identity.
 */
public record OutcomeDigest(String decision, String scopeKey, String scopeValue, int episodeCount, double meanReward, Map<String, Double> meanComponents) {}
