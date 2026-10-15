package ai.intentoms.decision.model;

import java.util.Map;

/** One element of a batch decide call (FR-DEC-07 batch mode). */
public record Invocation(String decisionName, Map<String, Object> input, DecisionOptions options) {}
