package ai.intentoms.sourcing.plan;

import ai.intentoms.sourcing.config.SourcingSettings;
import ai.intentoms.sourcing.model.PlanPackage;
import ai.intentoms.sourcing.model.SourcingPlan;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Composite plan score (FR-SRC-02, section 9 rule 1 — weights live in configuration):
 * {@code w_cost × (1 − normalisedCost) + w_time × onTime + w_soft × softScore + w_split × (1 − extraPackages / K)}.
 * Cost is normalised across the plans of one run (cheapest → 1, most expensive → 0); on-time is the plan's weakest
 * package; the soft score is the unit-weighted mean of the nodes' Jev composite scores (0.5 when the decision was
 * unavailable, NFR-05).
 */
public final class PlanScorer {
    public static final double NEUTRAL_SOFT = 0.5;

    private PlanScorer() {}

    public static List<SourcingPlan> score(List<SourcingPlan> plans, SourcingSettings.Weights w, int k, Map<String, Double> softScores) {
        if (plans.isEmpty()) return plans;
        BigDecimal min = null, max = null;
        for (SourcingPlan p : plans) {
            BigDecimal c = p.totalCost().amount();
            min = min == null || c.compareTo(min) < 0 ? c : min;
            max = max == null || c.compareTo(max) > 0 ? c : max;
        }
        List<SourcingPlan> out = new ArrayList<>(plans.size());
        for (SourcingPlan p : plans) {
            double normCost = normalisedCost(p.totalCost().amount(), min, max);
            double onTime = p.onTimeProbability();
            double soft = softScore(p, softScores);
            double splitTerm = 1.0 - Math.min(1.0, Math.max(0, p.packageCount() - 1) / (double) Math.max(1, k));
            double score = w.cost() * (1.0 - normCost) + w.time() * onTime + w.soft() * soft + w.split() * splitTerm;
            Map<String, Object> components = new LinkedHashMap<>();
            components.put("cost", round(1.0 - normCost));
            components.put("normalisedCost", round(normCost));
            components.put("time", round(onTime));
            components.put("soft", round(soft));
            components.put("split", round(splitTerm));
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("components", components);
            extra.put("weights", w.asMap());
            extra.put("score", round(score));
            out.add(p.withScore(round(score), extra));
        }
        return out;
    }

    static double normalisedCost(BigDecimal cost, BigDecimal min, BigDecimal max) {
        if (min == null || max == null || max.compareTo(min) == 0) return 0.0;
        return cost.subtract(min).doubleValue() / max.subtract(min).doubleValue();
    }

    /** Unit-weighted mean soft score of the plan's nodes. */
    public static double softScore(SourcingPlan p, Map<String, Double> softScores) {
        double sum = 0; int units = 0;
        for (PlanPackage pk : p.packages()) {
            int u = pk.units();
            sum += softScores.getOrDefault(pk.nodeId(), NEUTRAL_SOFT) * u;
            units += u;
        }
        return units == 0 ? NEUTRAL_SOFT : sum / units;
    }

    static double round(double v) { return Math.round(v * 10_000) / 10_000d; }
}
