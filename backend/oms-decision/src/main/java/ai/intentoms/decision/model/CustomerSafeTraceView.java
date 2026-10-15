package ai.intentoms.decision.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Customer-safe explanation variant (FR-XAI-04): hides margin, cost, risk and fraud data, provider internals and raw
 * probabilities so CS reps and agents can share it without leaking internal economics.
 */
public record CustomerSafeTraceView(String recordId, String decision, int version, Object value, Band band, String action, Layer layer, boolean requiresHuman,
                                    List<String> reasonCodes, List<Question> questions, Map<String, Object> state, Instant createdAt) {
    private static final Pattern INTERNAL = Pattern.compile("(?i)(margin|cost|risk|fraud|abuse|pnl|cogs|penalt|score|landed|labour|labor|velocity|limit)");

    public record Question(String key, String instructions, Object answer) {}

    public static CustomerSafeTraceView from(DecisionTrace t) {
        List<Question> qs = t.questions().stream()
            .filter(q -> !INTERNAL.matcher(q.key()).find() && !INTERNAL.matcher(q.instructions() == null ? "" : q.instructions()).find())
            .map(q -> new Question(q.key(), q.instructions(), q.answer() == null ? null : q.answer().value()))
            .toList();
        Object value = t.override() != null && t.override().get("value") != null ? t.override().get("value") : t.value();
        return new CustomerSafeTraceView(t.recordId(), t.decision(), t.version(), value, t.band(), t.action(), t.layer(), t.requiresHuman(),
            t.reasonCodes(), qs, redact(t.state()), t.createdAt());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> redact(Map<String, Object> m) {
        if (m == null) return Map.of();
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        m.forEach((k, v) -> {
            if (INTERNAL.matcher(k).find()) return;
            if (v instanceof Map<?, ?> inner) out.put(k, redact((Map<String, Object>) inner));
            else if (v instanceof List<?> l) out.put(k, l.stream().map(e -> e instanceof Map<?, ?> im ? redact((Map<String, Object>) im) : e).toList());
            else out.put(k, v);
        });
        return out;
    }
}
