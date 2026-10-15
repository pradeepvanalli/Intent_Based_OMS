package ai.intentoms.decision.studio;

import ai.intentoms.decision.model.EvaluationReport.CalibrationBin;

import java.util.ArrayList;
import java.util.List;

/**
 * Calibration maths shared by golden-set evaluation (GR-11) and drift monitoring (GR-12): ten equal confidence bins,
 * calibration error = sum over bins of (count/total) x |mean confidence - accuracy|.
 */
public final class Calibration {
    public static final int BINS = 10;

    private Calibration() {}

    /** One observation: stated confidence and whether the value turned out correct. */
    public record Observation(double confidence, boolean correct) {}

    public record Result(List<CalibrationBin> bins, double error) {}

    public static Result compute(List<Observation> obs) {
        int[] count = new int[BINS];
        double[] conf = new double[BINS];
        int[] correct = new int[BINS];
        for (Observation o : obs) {
            int b = (int) Math.min(BINS - 1, Math.max(0, Math.floor(o.confidence() * BINS)));
            count[b]++;
            conf[b] += o.confidence();
            if (o.correct()) correct[b]++;
        }
        List<CalibrationBin> bins = new ArrayList<>();
        double error = 0;
        int total = obs.size();
        for (int b = 0; b < BINS; b++) {
            double meanConf = count[b] == 0 ? 0 : conf[b] / count[b];
            double acc = count[b] == 0 ? 0 : correct[b] / (double) count[b];
            bins.add(new CalibrationBin(b / (double) BINS, (b + 1) / (double) BINS, count[b], round(meanConf), round(acc)));
            if (count[b] > 0) error += (count[b] / (double) total) * Math.abs(meanConf - acc);
        }
        return new Result(bins, round(error));
    }

    /** Loose equality for decision values: canonical JSON, with numeric tolerance and case-insensitive strings. */
    /**
     * Numeric answers are compared at rubric-level granularity: a Score answer is the probability-weighted level
     * (Jev returns 1.07 for "level 1"), so an expected integer level matches when the answer rounds to it
     * (|expected − actual| < 0.5). Strings compare case-insensitively; everything else by canonical JSON.
     */
    public static boolean valueEquals(Object expected, Object actual) {
        if (expected == null || actual == null) return expected == actual;
        if (expected instanceof Number a && actual instanceof Number b) return Math.abs(a.doubleValue() - b.doubleValue()) < SCORE_TOLERANCE;
        if (expected instanceof Number a && actual instanceof String s) { try { return Math.abs(a.doubleValue() - Double.parseDouble(s)) < SCORE_TOLERANCE; } catch (NumberFormatException e) { return false; } }
        if (expected instanceof String s && actual instanceof Number b) { try { return Math.abs(Double.parseDouble(s) - b.doubleValue()) < SCORE_TOLERANCE; } catch (NumberFormatException e) { return false; } }
        if (expected instanceof Boolean || actual instanceof Boolean) return String.valueOf(expected).equalsIgnoreCase(String.valueOf(actual));
        if (expected instanceof String s && actual instanceof String t) return s.trim().equalsIgnoreCase(t.trim());
        return ai.intentoms.platform.json.Json.canonical(expected).equals(ai.intentoms.platform.json.Json.canonical(actual));
    }

    /** Half a rubric level: a probability-weighted score matches the integer level it rounds to. */
    public static final double SCORE_TOLERANCE = 0.5;

    static double round(double d) { return Math.round(d * 10_000) / 10_000d; }
}
