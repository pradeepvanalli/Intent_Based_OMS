package ai.intentoms.decision.provider;

import ai.intentoms.decision.model.Answer;

import java.util.Map;

/** Typed answers plus usage, cost and latency (FR-DEC-06). {@code raw} keeps the provider payload for the trace. */
public record ProviderResponse(Map<String, Answer> answers, String modelVersion, int inputTokens, int outputTokens, double costUsd, long latencyMs, Map<String, Object> raw) {
    public ProviderResponse {
        answers = answers == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(answers));
        raw = raw == null ? Map.of() : raw;
    }
}
