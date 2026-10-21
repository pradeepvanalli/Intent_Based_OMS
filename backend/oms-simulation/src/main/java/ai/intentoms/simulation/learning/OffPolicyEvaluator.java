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

    /**
     * The doubly-robust estimate is the headline (it stays honest when the arm model is wrong and when propensities are
     * extreme); the plain inverse-propensity estimate is reported next to it (EX-01 names both) with its own interval.
     */
    public record Result(String method, int sampleSize, double baselineReward, double proposedRewardEstimate,
                          double liftEstimate, double ciLower, double ciUpper, int clippedRecords,
                          double ipsRewardEstimate, double ipsLift, double ipsCiLower, double ipsCiUpper) {
        public Result(String method, int sampleSize, double baselineReward, double proposedRewardEstimate, double liftEstimate, double ciLower, double ciUpper, int clippedRecords) {
            this(method, sampleSize, baselineReward, proposedRewardEstimate, liftEstimate, ciLower, ciUpper, clippedRecords, 0, 0, 0, 0);
        }

        public Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("method", method);
            m.put("sampleSize", sampleSize);
            m.put("baselineReward", baselineReward);
            m.put("proposedRewardEstimate", proposedRewardEstimate);
            m.put("liftEstimate", liftEstimate);
            m.put("confidenceInterval", Map.of("lower", ciLower, "upper", ciUpper));
            m.put("clippedRecords", clippedRecords);
            Map<String, Object> ips = new LinkedHashMap<>();
            ips.put("proposedRewardEstimate", ipsRewardEstimate);
            ips.put("liftEstimate", ipsLift);
            ips.put("confidenceInterval", Map.of("lower", ipsCiLower, "upper", ipsCiUpper));
            m.put("ips", ips);
            m.put("estimators", java.util.List.of("doubly_robust", "inverse_propensity"));
            return m;
        }
    }

    public static Result evaluate(List<LoggedRecord> records, PolicyChangeset changeset) {
        return evaluate(records, r -> ProposedArmSimulator.proposedArm(r, changeset));
    }

    /** Overload taking the proposed-arm function directly, for tests that don't need a real PolicyChangeset. */
    public static Result evaluate(List<LoggedRecord> records, Function<LoggedRecord, String> proposedArmOf) {
        return evaluate(records, proposedArmOf, PROPENSITY_FLOOR);
    }

    /**
     * As {@link #evaluate(List, Function)}, with an explicit propensity-clip floor (D4-1/EX-01). The default floor
     * exists so one near-zero-propensity record can't dominate the estimate, but a decision under the governed
     * exploration floor can legitimately log a true propensity below it (an 8-arm decision exploring at a 10% share
     * logs 0.1/7 ≈ 0.014 for its explored arm); clipping that up to the default floor would truncate the floor's own
     * propensities and bias the correction by construction. Callers pass the lower of the default and that decision's
     * own floor; everything else about the estimator is unchanged.
     */
    public static Result evaluate(List<LoggedRecord> records, Function<LoggedRecord, String> proposedArmOf, double propensityFloor) {
        int n = records.size();
        if (n == 0) return new Result("ips_doubly_robust", 0, 0, 0, 0, 0, 0, 0);

        double baselineReward = records.stream().mapToDouble(LoggedRecord::reward).average().orElse(0);
        Map<String, Double> armMeanReward = perArmMeanReward(records);

        int clipped = 0;
        double[] drTerms = new double[n];
        double[] ipsTerms = new double[n];
        for (int i = 0; i < n; i++) {
            LoggedRecord r = records.get(i);
            String proposed = proposedArmOf.apply(r);
            double propensity = r.propensity();
            boolean wasClipped = propensity < propensityFloor;
            if (wasClipped) { propensity = propensityFloor; clipped++; }

            double proposedBaseline = proposed == null ? 0.0 : armMeanReward.getOrDefault(proposed, baselineReward);
            double loggedBaseline = armMeanReward.getOrDefault(r.chosenArm(), baselineReward);
            boolean matches = proposed != null && proposed.equals(r.chosenArm());
            ipsTerms[i] = matches ? (r.reward() / propensity) : 0.0;
            // Doubly robust: direct-method baseline for the arm the proposed policy would pick, corrected by the
            // IPS-weighted residual only on the records where the logged and proposed arms actually agree.
            drTerms[i] = proposedBaseline + (matches ? (r.reward() - loggedBaseline) / propensity : 0.0);
        }

        double proposedEstimate = mean(drTerms);
        double variance = sampleVariance(drTerms, proposedEstimate);
        double se = Math.sqrt(variance / n);
        double lift = proposedEstimate - baselineReward;
        double ciHalfWidth = 1.96 * se;

        double ipsEstimate = mean(ipsTerms);
        double ipsSe = Math.sqrt(sampleVariance(ipsTerms, ipsEstimate) / n);
        double ipsLift = ipsEstimate - baselineReward;

        return new Result("ips_doubly_robust", n, round(baselineReward), round(proposedEstimate), round(lift),
            round(lift - ciHalfWidth), round(lift + ciHalfWidth), clipped,
            round(ipsEstimate), round(ipsLift), round(ipsLift - 1.96 * ipsSe), round(ipsLift + 1.96 * ipsSe));
    }

    /**
     * EX-01 for a stochastic target policy (a bandit with another exploration share, the bandit switched on or off): each
     * record's weight is the probability the target policy gives the logged arm over the logged propensity, the latter
     * floored at {@link #PROPENSITY_FLOOR} (clipped). IPS is the weighted reward; doubly robust adds the direct-method
     * value of the target's whole arm distribution (per-arm mean reward) and corrects it by the weighted residual. A
     * record whose target distribution is unknown (null) is left out.
     */
    public static Result evaluateStochastic(List<LoggedRecord> records, Function<LoggedRecord, Map<String, Double>> targetDistribution) {
        List<LoggedRecord> usable = new java.util.ArrayList<>();
        List<Map<String, Double>> targets = new java.util.ArrayList<>();
        for (LoggedRecord r : records) {
            Map<String, Double> t = targetDistribution.apply(r);
            if (t == null || t.isEmpty()) continue;
            usable.add(r);
            targets.add(t);
        }
        int n = usable.size();
        if (n == 0) return new Result("ips_doubly_robust", 0, 0, 0, 0, 0, 0, 0);
        double baselineReward = usable.stream().mapToDouble(LoggedRecord::reward).average().orElse(0);
        Map<String, Double> q = perArmMeanReward(usable);
        int clipped = 0;
        double[] dr = new double[n], ips = new double[n];
        for (int i = 0; i < n; i++) {
            LoggedRecord r = usable.get(i);
            Map<String, Double> t = targets.get(i);
            double mu = r.propensity();
            if (mu < PROPENSITY_FLOOR) { mu = PROPENSITY_FLOOR; clipped++; }
            double w = t.getOrDefault(r.chosenArm(), 0.0) / mu;
            double direct = 0;
            for (var e : t.entrySet()) direct += e.getValue() * q.getOrDefault(e.getKey(), baselineReward);
            ips[i] = w * r.reward();
            dr[i] = direct + w * (r.reward() - q.getOrDefault(r.chosenArm(), baselineReward));
        }
        double est = mean(dr), se = Math.sqrt(sampleVariance(dr, est) / n), lift = est - baselineReward;
        double ipsEst = mean(ips), ipsSe = Math.sqrt(sampleVariance(ips, ipsEst) / n), ipsLift = ipsEst - baselineReward;
        return new Result("ips_doubly_robust", n, round(baselineReward), round(est), round(lift), round(lift - 1.96 * se), round(lift + 1.96 * se), clipped,
            round(ipsEst), round(ipsLift), round(ipsLift - 1.96 * ipsSe), round(ipsLift + 1.96 * ipsSe));
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
