package ai.intentoms.learning.experiment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EX-03: an always-valid sequential test (mixture SPRT, normal approximation) for the difference in mean reward
 * between a canary slice and its control. The statistic can be checked after every batch of outcomes without
 * inflating the false-positive rate, which is what lets a canary stop early instead of waiting out a fixed horizon.
 *
 * <p>With the estimate {@code d = mean(treatment) - mean(control)}, its variance {@code V = s_t^2/n_t + s_c^2/n_c} and a
 * normal mixing prior on the true difference with variance {@code tau^2}, the mixture likelihood ratio against "no
 * difference" is {@code sqrt(V/(V+tau^2)) * exp(d^2 tau^2 / (2 V (V+tau^2)))}. The null is rejected once it reaches
 * {@code 1/alpha}. {@code tau} defaults to a fifth of the pooled standard deviation, a moderate expected effect.
 */
public final class Msprt {
    private Msprt() {}

    public enum Verdict { CONTINUE, TREATMENT_BETTER, TREATMENT_WORSE, INSUFFICIENT_DATA }

    public record Result(Verdict verdict, double statistic, double threshold, double difference, double variance, int nTreatment, int nControl,
                         double meanTreatment, double meanControl) {
        public Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("verdict", verdict.name());
            m.put("statistic", round(statistic));
            m.put("threshold", round(threshold));
            m.put("difference", round(difference));
            m.put("standardError", round(Math.sqrt(Math.max(variance, 0))));
            m.put("treatment", Map.of("n", nTreatment, "meanReward", round(meanTreatment)));
            m.put("control", Map.of("n", nControl, "meanReward", round(meanControl)));
            return m;
        }
    }

    public static Result test(List<Double> treatment, List<Double> control, double alpha, int minPerArm, Double tau2Override) {
        int nt = treatment.size(), nc = control.size();
        double mt = mean(treatment), mc = mean(control);
        double threshold = 1.0 / alpha;
        if (nt < minPerArm || nc < minPerArm) return new Result(Verdict.INSUFFICIENT_DATA, 0, threshold, mt - mc, 0, nt, nc, mt, mc);
        double vt = variance(treatment, mt), vc = variance(control, mc);
        double v = vt / nt + vc / nc;
        double d = mt - mc;
        if (v <= 1e-12) {
            Verdict verdict = Math.abs(d) < 1e-12 ? Verdict.CONTINUE : d > 0 ? Verdict.TREATMENT_BETTER : Verdict.TREATMENT_WORSE;
            return new Result(verdict, verdict == Verdict.CONTINUE ? 1 : Double.POSITIVE_INFINITY, threshold, d, v, nt, nc, mt, mc);
        }
        double pooledVar = ((nt - 1) * vt + (nc - 1) * vc) / Math.max(1, nt + nc - 2);
        double tau2 = tau2Override != null && tau2Override > 0 ? tau2Override : Math.max(1e-9, 0.04 * pooledVar);
        double lambda = Math.sqrt(v / (v + tau2)) * Math.exp(d * d * tau2 / (2 * v * (v + tau2)));
        Verdict verdict = lambda >= threshold ? (d > 0 ? Verdict.TREATMENT_BETTER : Verdict.TREATMENT_WORSE) : Verdict.CONTINUE;
        return new Result(verdict, lambda, threshold, d, v, nt, nc, mt, mc);
    }

    static double mean(List<Double> xs) { return xs.isEmpty() ? 0 : xs.stream().mapToDouble(Double::doubleValue).average().orElse(0); }

    static double variance(List<Double> xs, double mean) {
        if (xs.size() < 2) return 0;
        double ss = 0;
        for (double x : xs) ss += (x - mean) * (x - mean);
        return ss / (xs.size() - 1);
    }

    private static double round(double v) { return Double.isFinite(v) ? Math.round(v * 10_000d) / 10_000d : v; }
}
