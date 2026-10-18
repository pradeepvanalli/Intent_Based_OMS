package ai.intentoms.decision.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Customer-safe explanation variant (FR-XAI-04): hides margin, cost, risk and fraud data, provider internals and raw
 * probabilities so CS reps and agents can share it without leaking internal economics. XP-05: {@code learnedContext}
 * says in plain words which learned knowledge shaped the decision (for example "how reliably each fulfilment location
 * ships on time"), never the learned values, and never risk, fraud, calibration or experiment details.
 */
public record CustomerSafeTraceView(String recordId, String decision, int version, Object value, Band band, String action, Layer layer, boolean requiresHuman,
                                    List<String> reasonCodes, List<Question> questions, Map<String, Object> state, Instant createdAt,
                                    List<String> learnedContext) {
    private static final Pattern INTERNAL = Pattern.compile("(?i)(margin|cost|risk|fraud|abuse|pnl|cogs|penalt|score|landed|labour|labor|velocity|limit)");

    /** Learned fact keys that may be mentioned to a customer, with the wording to use. Anything else is withheld. */
    private static final Map<String, String> SAFE_FACTS = Map.ofEntries(
        Map.entry("NODE_ON_TIME_RATE", "how reliably each fulfilment location has shipped on time recently"),
        Map.entry("NODE_REJECT_RATE", "how often each fulfilment location has been able to fill orders"),
        Map.entry("LANE_TRANSIT_DAYS_P50", "how long deliveries on this route have actually taken"),
        Map.entry("LANE_TRANSIT_DAYS_P90", "how long deliveries on this route have actually taken"),
        Map.entry("LANE_ON_TIME_RATE", "how often deliveries on this route have arrived on time"),
        Map.entry("LANE_CALIBRATION_OFFSET", "how often deliveries on this route have arrived on time"),
        Map.entry("SPLIT_TOLERANCE", "your past preferences about receiving an order in more than one package"),
        Map.entry("SPEED_VS_COST", "your past choices between faster and cheaper delivery"),
        Map.entry("PICKUP_PREFERENCE", "whether you have preferred store pickup before"),
        Map.entry("PREFERRED_CONTACT_CHANNEL", "the way you prefer us to contact you"),
        Map.entry("PROMISE_BUFFER_DAYS", "how accurate our delivery promises have been"),
        Map.entry("STOCK_AGE_DAYS", "where stock is available"),
        Map.entry("INBOUND_RELIABILITY", "when new stock is expected to arrive"));

    public record Question(String key, String instructions, Object answer) {}

    public static CustomerSafeTraceView from(DecisionTrace t) {
        List<Question> qs = t.questions().stream()
            .filter(q -> !INTERNAL.matcher(q.key()).find() && !INTERNAL.matcher(q.instructions() == null ? "" : q.instructions()).find())
            .map(q -> new Question(q.key(), q.instructions(), q.answer() == null ? null : q.answer().value()))
            .toList();
        Object value = t.override() != null && t.override().get("value") != null ? t.override().get("value") : t.value();
        return new CustomerSafeTraceView(t.recordId(), t.decision(), t.version(), value, t.band(), t.action(), t.layer(), t.requiresHuman(),
            t.reasonCodes(), qs, redact(t.state()), t.createdAt(), learnedContext(t.learning()));
    }

    /** Plain-language mentions of the learned knowledge that was actually served to this decision. */
    static List<String> learnedContext(Map<String, Object> learning) {
        if (learning == null || !(learning.get("learnedFacts") instanceof List<?> facts)) return List.of();
        Set<String> out = new LinkedHashSet<>();
        for (Object f : facts) {
            if (!(f instanceof Map<?, ?> m) || !"LEARNED".equals(m.get("source"))) continue;
            String phrase = SAFE_FACTS.get(String.valueOf(m.get("factKey")));
            if (phrase != null) out.add("This decision took into account " + phrase + ".");
        }
        return new ArrayList<>(out);
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
