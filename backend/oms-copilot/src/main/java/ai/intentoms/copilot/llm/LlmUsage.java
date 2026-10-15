package ai.intentoms.copilot.llm;

/** One completed (or failed) model call as written to {@code copilot_usage} (GR-17). */
public record LlmUsage(String feature, String model, int inputTokens, int outputTokens, double costUsd, long latencyMs, String status) {}
