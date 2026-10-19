package ai.intentoms.learning.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * FS-04: the typed lever of a weight hypothesis. A learner that finds better plan-score weights (the sourcing weight
 * learner on {@code sourcing.weights}) raises a {@link Hypothesis} carrying this lever; the Author turns it into a
 * {@code WeightChange} proposal on {@code config:<configKey>} and attaches the evidence the owning learner produces for
 * it ({@link Evidence#report}, the {@code plan_rescoring_replay}). The lever round-trips through the hypothesis's JSON
 * {@code lever} map ({@link #toLever()} / {@link #of(Hypothesis)}).
 *
 * @param current      the live weights the learner started from (the Author refuses the lever if they moved since)
 * @param proposed     the suggested weights, normalised to sum to 1
 * @param correlations each component's correlation with the reward, the reason for the move
 */
public record WeightLever(String configKey, Map<String, Double> current, Map<String, Double> proposed, Map<String, Double> correlations, int samples, int windowDays) {
    public static final String ACTION = "reweight";
    public static final String LEVER = "weightChange";
    /** A weight that moves by less than this is left out of the proposal. */
    static final double MIN_MOVE = 0.005;

    public String target() { return "config:" + configKey; }

    public Map<String, Object> toLever() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", ACTION);
        m.put("lever", LEVER);
        m.put("target", target());
        m.put("configKey", configKey);
        m.put("current", current);
        m.put("proposed", proposed);
        m.put("correlations", correlations == null ? Map.of() : correlations);
        m.put("samples", samples);
        m.put("windowDays", windowDays);
        return m;
    }

    /** The weight lever of a hypothesis, when it carries one. */
    public static Optional<WeightLever> of(Hypothesis h) {
        if (h == null || h.lever() == null) return Optional.empty();
        Map<String, Object> l = h.lever();
        if (!ACTION.equals(l.get("action")) && !LEVER.equals(l.get("lever"))) return Optional.empty();
        String key = l.get("configKey") != null ? String.valueOf(l.get("configKey"))
            : l.get("target") != null && String.valueOf(l.get("target")).startsWith("config:") ? String.valueOf(l.get("target")).substring("config:".length()) : null;
        Map<String, Double> current = numbers(l.get("current")), proposed = numbers(l.get("proposed"));
        if (key == null || key.isBlank() || current.isEmpty() || proposed.isEmpty()) return Optional.empty();
        return Optional.of(new WeightLever(key, current, proposed, numbers(l.get("correlations")),
            l.get("samples") instanceof Number n ? n.intValue() : 0, l.get("windowDays") instanceof Number w ? w.intValue() : 0));
    }

    /** One {@code WeightChange} per component that moves by at least half a point. */
    public List<PolicyChange> changes() {
        List<PolicyChange> out = new ArrayList<>();
        for (var e : proposed.entrySet()) {
            Double from = current.get(e.getKey());
            if (from == null || e.getValue() == null || Math.abs(e.getValue() - from) < MIN_MOVE) continue;
            out.add(new PolicyChange.WeightChange(configKey, e.getKey(), from, e.getValue()));
        }
        return out;
    }

    private static Map<String, Double> numbers(Object o) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> m) for (var e : m.entrySet()) if (e.getValue() instanceof Number n) out.put(String.valueOf(e.getKey()), n.doubleValue());
        return out;
    }

    /** What the learner that owns a weight vector provides to the Author: today's weights and the evidence for a move. */
    public interface Evidence {
        boolean supports(String configKey);

        /** The weights in force now. */
        Map<String, Double> liveWeights();

        /** The evaluation report for moving from {@code current} to {@code proposed} (FS-04: {@code plan_rescoring_replay}). */
        Map<String, Object> report(Map<String, Double> current, Map<String, Double> proposed);
    }
}
