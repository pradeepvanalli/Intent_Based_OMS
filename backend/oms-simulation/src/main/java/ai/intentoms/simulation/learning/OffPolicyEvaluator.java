package ai.intentoms.simulation.learning;

import ai.intentoms.learning.model.PolicyChangeset;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * EX-01: off-policy evaluation of a proposed policy against logged historical decisions, using inverse propensity
 * scoring (IPS) corrected into a doubly-robust (DR) estimate, with a 95% confidence interval on the lift. Pure and
 * side-effect free so it's independently unit-testable (requirements doc §13: "statistics decide").
 *
 * <p>Propensity weights are clipped at {@link #PROPENSITY_FLOOR} (requirements doc §13's named mitigation for
 * "off-policy estimates are noisy when propensities are near 0 or 1") so one near-zero-propensity record can't
 * dominate the estimate.
 */
public final class OffPolicyEvaluator {
    public static final double PROPENSITY_FLOOR = 0.05;

    private OffPolicyEvaluator() {}

    public record Result(String method, int sampleSize, double baselineReward, double proposedRewardEstimate,
                          double liftEstimate, double ciLower, double ciUpper, int clippedRecords) {
        public Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("method", method);
            m.put("sampleSize", sampleSize);
            m.put("baselineReward", baselineReward);
            m.put("proposedRewardEstimate", proposedRewardEstimate);
            m.put("liftEstimate", liftEstimate);
            m.put("confidenceInterval", Map.of("lower", ciLower, "upper", ciUpper));
            m.put("clippedRecords", clippedRecords);
            return m;
        }
    }

    public static Result evaluate(List<LoggedRecord> records, PolicyChangeset changeset) {
        return evaluate(records, r -> ProposedArmSimulator.proposedArm(r, changeset));
    }

    /** Overload taking the proposed-arm function directly, for tests that don't need a real PolicyChangeset. */
    public static Result evaluate(List<LoggedRecord> records, Function<LoggedRecord, String> proposedArmOf) {
        int n = records.size();
        if (n == 0) return new Result("ips_doubly_robust", 0, 0, 0, 0, 0, 0, 0);

        double baselineReward = records.stream().mapToDouble(LoggedRecord::reward).average().orElse(0);
        Map<String, Double> armMeanReward = perArmMeanReward(records);

        int clipped = 0;
        double[] drTerms = new double[n];
        for (int i = 0; i < n; i++) {
            LoggedRecord r = records.get(i);
            String proposed = proposedArmOf.apply(r);
            double propensity = r.propensity();
            boolean wasClipped = propensity < PROPENSITY_FLOOR;
            if (wasClipped) { propensity = PROPENSITY_FLOOR; clipped++; }

            double proposedBaseline = proposed == null ? 0.0 : armMeanReward.getOrDefault(proposed, baselineReward);
            double loggedBaseline = armMeanReward.getOrDefault(r.chosenArm(), baselineReward);
            boolean matches = proposed != null && proposed.equals(r.chosenArm());
            double ips = matches ? (r.reward() / propensity) : 0.0;
            // Doubly robust: direct-method baseline for the arm the proposed policy would pick, corrected by the
            // IPS-weighted residual only on the records where the logged and proposed arms actually agree.
            drTerms[i] = proposedBaseline + (matches ? (r.reward() - loggedBaseline) / propensity : 0.0);
        }

        double proposedEstimate = mean(drTerms);
        double variance = sampleVariance(drTerms, proposedEstimate);
        double se = Math.sqrt(variance / n);
        double lift = proposedEstimate - baselineReward;
        double ciHalfWidth = 1.96 * se;

        return new Result("ips_doubly_robust", n, round(baselineReward), round(proposedEstimate), round(lift),
            round(lift - ciHalfWidth), round(lift + ciHalfWidth), clipped);
    }

    private static Map<String, Double> perArmMeanReward(List<LoggedRecord> records) {
        Map<String, List<Double>> byArm = new LinkedHashMap<>();
        for (LoggedRecord r : records) byArm.computeIfAbsent(r.chosenArm(), k -> new java.util.ArrayList<>()).add(r.reward());
        Map<String, Double> out = new LinkedHashMap<>();
        byArm.forEach((arm, rewards) -> out.put(arm, rewards.stream().mapToDouble(Double::doubleValue).average().orElse(0)));
        return out;
    }

    private static double mean(double[] xs) { double s = 0; for (double x : xs) s += x; return s / xs.length; }

    private static double sampleVariance(double[] xs, double mean) {
        if (xs.length < 2) return 0;
        double ss = 0;
        for (double x : xs) ss += (x - mean) * (x - mean);
        return ss / (xs.length - 1);
    }

    private static double round(double v) { return Math.round(v * 1_0000.0) / 1_0000.0; }
}
