package ai.intentoms.decision.model;

import java.util.Map;

/** One labelled golden case (GR-11): input and expected {value, band?}. */
public record GoldenCase(String goldenSet, String caseId, Map<String, Object> input, Map<String, Object> expected, String notes) {
    public Object expectedValue() { return expected == null ? null : expected.get("value"); }
}
