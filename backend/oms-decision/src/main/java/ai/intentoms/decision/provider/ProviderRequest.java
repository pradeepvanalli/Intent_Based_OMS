package ai.intentoms.decision.provider;

import ai.intentoms.decision.model.QuestionDef;

import java.util.Map;

/**
 * One provider call (FR-DEC-08): the typed state (string, map or list), the questions to ask in a single call (section
 * 9 rule 3 speculative fan-out), the remaining time budget and an optional model override.
 */
public record ProviderRequest(Object state, Map<String, QuestionDef> questions, int timeoutMs, String model) {
    public ProviderRequest {
        questions = questions == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(questions));
        if (timeoutMs <= 0) timeoutMs = 800;
    }
}
