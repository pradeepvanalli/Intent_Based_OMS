package ai.intentoms.decision.model;

import java.util.Map;

/** Composite scoring (FR-DEC-04): weighted average of normalised Score answers written to {@code answers[outputKey]}. */
public record CompositeDef(Map<String, Double> weights, String outputKey) {
    public CompositeDef {
        weights = weights == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(weights));
        if (outputKey == null || outputKey.isBlank()) outputKey = "composite";
    }
}
