package ai.intentoms.learning.model;

import ai.intentoms.decision.model.DecisionRecord;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * OC-06: reads the {@code human_resolution} label a person's task completion leaves on a decision record. The label
 * carries the value the person chose ({@code value}), the task's outcome word ({@code outcome}: accept / override /
 * …) and the reason code. Older labels stored either the answer or the outcome word in {@code action}; a word that is
 * one of the decision's answers is the answer, otherwise an accepting word means the model's answer and "override" means
 * the record's overridden value, so learners read every label the same way.
 */
public final class HumanResolution {
    private HumanResolution() {}

    public static final String LABEL = "human_resolution";
    /** Outcome words that keep the recommendation (same set the escalation subscriber uses). */
    public static final Set<String> ACCEPT = Set.of("accept", "accepted", "approved", "approve", "agree", "confirm", "confirmed", "ok", "yes");
    private static final Set<String> OVERRIDE = Set.of("override", "overridden", "reject", "rejected", "change", "changed");

    @SuppressWarnings("unchecked")
    public static Optional<Map<String, Object>> label(DecisionRecord r) {
        if (r == null || r.labels() == null || !(r.labels().get(LABEL) instanceof Map<?, ?> m)) return Optional.empty();
        return Optional.of((Map<String, Object>) m);
    }

    /** The value the person settled on, or empty when the label does not say (an override with no value given). */
    public static Optional<String> value(DecisionRecord r) {
        Optional<Map<String, Object>> l = label(r);
        if (l.isEmpty()) return Optional.empty();
        Object v = l.get().get("value");
        if (v != null && !String.valueOf(v).isBlank()) return Optional.of(String.valueOf(v));
        Object action = l.get().get("action");
        if (action == null || String.valueOf(action).isBlank()) return Optional.empty();
        String a = String.valueOf(action).toLowerCase(Locale.ROOT);
        if (namesAnAnswer(r, a)) return Optional.of(String.valueOf(action)); // "approve" on a decision whose answers include approve
        if (ACCEPT.contains(a)) return r.value() == null ? Optional.empty() : Optional.of(String.valueOf(r.value()));
        if (OVERRIDE.contains(a)) return r.overriddenValue() == null ? Optional.empty() : Optional.of(String.valueOf(r.overriddenValue()));
        return Optional.of(String.valueOf(action)); // a legacy label that stored the answer itself
    }

    private static boolean namesAnAnswer(DecisionRecord r, String a) {
        if (r.value() != null && a.equalsIgnoreCase(String.valueOf(r.value()))) return true;
        if (r.overriddenValue() != null && a.equalsIgnoreCase(String.valueOf(r.overriddenValue()))) return true;
        return r.arms() != null && r.arms().stream().anyMatch(arm -> a.equalsIgnoreCase(arm));
    }

    /** Whether the person kept the model's answer; empty when the person's value is not known. */
    public static Optional<Boolean> agreedWithModel(DecisionRecord r) {
        return value(r).map(v -> r.value() != null && v.equalsIgnoreCase(String.valueOf(r.value())));
    }

    /** True when the record carries a resolution whose value is known. */
    public static boolean hasValue(DecisionRecord r) { return value(r).isPresent(); }
}
