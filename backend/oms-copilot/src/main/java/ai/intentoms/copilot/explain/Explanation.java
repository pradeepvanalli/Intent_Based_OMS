package ai.intentoms.copilot.explain;

/** A generated explanation (FR-XAI-03) labelled as such and linked to its raw trace. */
public record Explanation(String text, String audience, boolean generated, String recordId, String model, String traceUrl, String fallbackReason) {}
