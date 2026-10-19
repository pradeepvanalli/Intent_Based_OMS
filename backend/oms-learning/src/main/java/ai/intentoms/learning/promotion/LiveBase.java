package ai.intentoms.learning.promotion;

import ai.intentoms.decision.model.Bands;
import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.learning.governance.LiveFamilies;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.PolicyChangeset;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * D-2 (GV-02): what is live now for a proposal's target, so that a change is judged against the live artifact and never
 * against the snapshot it was drafted from.
 *
 * <ul>
 *   <li><b>Decision targets</b>: the live version and the values a change can move (band thresholds, composite weights,
 *       the provider, fallback, timeout and default fields). A change drafted against another version, or whose
 *       {@code from} no longer matches the live value, is <em>stale</em>: evaluation and promotion refuse it with
 *       "re-draft against the live version", and the max step is measured from the live value.</li>
 *   <li><b>Live use of a learned family</b> ({@code parameter:live:<family>}): the range that is live now. Moving an
 *       existing range is a step: one promotion may move {@code min} or {@code max} by at most the family's
 *       {@link LiveFamilies.Domain#maxStepLimit() max step limit} (half its domain), else a person must give a reason.</li>
 * </ul>
 */
public record LiveBase(String target, Integer version, Map<String, Object> values, Double rangeMin, Double rangeMax, Double rangeStepLimit) {
    /** The learners round what they read to three decimals; a {@code from} within this of the live value is current. */
    public static final double FROM_TOLERANCE = 5e-4;
    public static final String REDRAFT = "re-draft against the live version";

    public LiveBase {
        values = values == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /** Nothing live to compare with (a config or fact target, or a decision without a live version). */
    public static LiveBase none() { return new LiveBase(null, null, Map.of(), null, null, null); }

    /** The live definition of a decision: its version and every value a decision change can move. */
    public static LiveBase of(DecisionDefinition live) {
        Map<String, Object> v = new LinkedHashMap<>();
        // The configured bands (a T3 decision's effective "never act" band is not what a ThresholdChange edits).
        Bands b = live.bands() != null ? live.bands() : live.effectiveBands();
        v.put("band:act", b.actGte());
        v.put("band:confirm", b.confirmGte());
        if (live.composite() != null) live.composite().weights().forEach((k, w) -> v.put("weight:" + k, w));
        v.put("field:provider", live.provider());
        v.put("field:fallbackProvider", live.fallbackProvider());
        v.put("field:timeoutMs", live.timeoutMs());
        v.put("field:default", live.dflt());
        return new LiveBase("decision:" + live.name(), live.version(), v, null, null, null);
    }

    /** The range a learned family is live with now; {@code min}/{@code max} null when the live use has no such bound. */
    public static LiveBase ofLiveRange(String family, Double min, Double max) {
        Double limit = LiveFamilies.of(family).map(LiveFamilies.Domain::maxStepLimit).orElse(null);
        return new LiveBase("parameter:live:" + family, null, Map.of(), min, max, limit);
    }

    /**
     * Why the changeset no longer fits the live decision, ending in "re-draft against the live version"; null when it is
     * current or there is no live decision to compare with.
     */
    public String staleness(PolicyChangeset cs) {
        if (version == null || cs == null) return null;
        String name = target.substring(target.indexOf(':') + 1);
        if (cs.baseVersion() > 0 && cs.baseVersion() != version) {
            return "it was drafted against version " + cs.baseVersion() + " of " + name + ", but version " + version + " is live now; " + REDRAFT;
        }
        for (PolicyChange c : cs.changes()) {
            String key = key(c);
            Object from = from(c);
            if (key == null || from == null || !values.containsKey(key)) continue;
            Object live = values.get(key);
            if (!same(from, live)) {
                return "it moves " + label(c) + " from " + fmt(from) + ", but the live value is " + fmt(live) + " (version " + version + "); " + REDRAFT;
            }
        }
        return null;
    }

    /** The live value a numeric decision change moves from, or null when unknown (the gate then uses the change's own {@code from}). */
    public Double liveFrom(PolicyChange c) {
        String key = key(c);
        return key != null && values.get(key) instanceof Number n ? n.doubleValue() : null;
    }

    /**
     * GV-02 for live use: moving an existing live range by more than the family's max step limit in one promotion. Null
     * when the family is not live with a range, the change revokes live use, or the move fits.
     */
    public String rangeStepViolation(PolicyChangeset cs) {
        if (rangeStepLimit == null || (rangeMin == null && rangeMax == null) || cs == null || cs.changes().isEmpty()) return null;
        if (!(cs.changes().get(0) instanceof PolicyChange.ParameterChange pc) || !(pc.to() instanceof Map<?, ?> to)) return null;
        if (Boolean.FALSE.equals(to.get("enabled"))) return null;
        Double toMin = num(to.get("min")), toMax = num(to.get("max"));
        double minMove = rangeMin != null && toMin != null ? Math.abs(toMin - rangeMin) : 0;
        double maxMove = rangeMax != null && toMax != null ? Math.abs(toMax - rangeMax) : 0;
        double step = Math.max(minMove, maxMove);
        if (step <= rangeStepLimit + 1e-9) return null;
        String family = target.substring("parameter:live:".length());
        return String.format(Locale.ROOT, "it moves the live range of %s by %s (min %s to %s, max %s to %s), more than the %s one promotion may move it (GV-02)",
            family, fmt(step), fmt(rangeMin), fmt(toMin), fmt(rangeMax), fmt(toMax), fmt(rangeStepLimit));
    }

    static String key(PolicyChange c) {
        return switch (c) {
            case PolicyChange.ThresholdChange tc -> "band:" + tc.band().toLowerCase(Locale.ROOT);
            case PolicyChange.WeightChange wc -> "weight:" + wc.weightKey();
            case PolicyChange.ParameterChange pc -> ChangesetApplier.DECISION_FIELDS.contains(pc.parameter()) ? "field:" + pc.parameter() : null;
            default -> null;
        };
    }

    private static Object from(PolicyChange c) {
        return switch (c) {
            case PolicyChange.ThresholdChange tc -> tc.from();
            case PolicyChange.WeightChange wc -> wc.from();
            case PolicyChange.ParameterChange pc -> pc.from();
            default -> null;
        };
    }

    private static String label(PolicyChange c) {
        return switch (c) {
            case PolicyChange.ThresholdChange tc -> "the " + tc.band() + " threshold";
            case PolicyChange.WeightChange wc -> "the " + wc.weightKey() + " weight";
            case PolicyChange.ParameterChange pc -> pc.parameter();
            default -> c.getClass().getSimpleName();
        };
    }

    private static boolean same(Object from, Object live) {
        if (from instanceof Number f && live instanceof Number l) return Math.abs(f.doubleValue() - l.doubleValue()) <= FROM_TOLERANCE + 1e-9;
        return String.valueOf(from).equals(String.valueOf(live));
    }

    private static Double num(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o == null) return null;
        try { return Double.valueOf(String.valueOf(o)); } catch (NumberFormatException e) { return null; }
    }

    private static String fmt(Object o) {
        if (o instanceof Double d) return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf(d.longValue()) : String.format(Locale.ROOT, "%.4f", d).replaceAll("0+$", "").replaceAll("\\.$", "");
        return o == null ? "none" : String.valueOf(o);
    }
}
