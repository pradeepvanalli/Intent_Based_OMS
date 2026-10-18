package ai.intentoms.learning.learner;

import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.TypedValue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * FS-05: a node's backlog-to-breach curve, stored as {@code NODE_BACKLOG_BREACH_CURVE} points
 * {@code "<backlogUnits>:<breachProbability>"} sorted by backlog. Reading interpolates linearly between points and holds
 * the end values flat beyond them; the curve is made monotone (more backlog never means less risk) when it is fitted.
 */
public final class BacklogBreachCurve {
    private BacklogBreachCurve() {}

    public record Point(double backlog, double breach) {}

    public static List<Point> parse(LearnedFact fact) {
        if (fact == null || !(fact.value() instanceof TypedValue.ListValue lv)) return List.of();
        List<Point> out = new ArrayList<>();
        for (String s : lv.values()) {
            int i = s.indexOf(':');
            if (i < 0) continue;
            try { out.add(new Point(Double.parseDouble(s.substring(0, i)), Double.parseDouble(s.substring(i + 1)))); }
            catch (NumberFormatException ignored) { /* skip a malformed point */ }
        }
        out.sort(Comparator.comparingDouble(Point::backlog));
        return out;
    }

    public static Optional<Double> at(List<Point> curve, double backlog) {
        if (curve.isEmpty()) return Optional.empty();
        if (backlog <= curve.get(0).backlog()) return Optional.of(curve.get(0).breach());
        for (int i = 1; i < curve.size(); i++) {
            Point a = curve.get(i - 1), b = curve.get(i);
            if (backlog <= b.backlog()) {
                double t = b.backlog() == a.backlog() ? 1.0 : (backlog - a.backlog()) / (b.backlog() - a.backlog());
                return Optional.of(a.breach() + t * (b.breach() - a.breach()));
            }
        }
        return Optional.of(curve.get(curve.size() - 1).breach());
    }

    /**
     * Fits the curve from (backlog, breached 0/1) samples: equal-count buckets (at least {@code minPerBucket} samples
     * each), a Beta(1,1)-smoothed breach rate per bucket at the bucket's median backlog, then pooled adjacent
     * violators so the rate never falls as backlog rises.
     */
    public static List<Point> fit(List<double[]> samples, int buckets, int minPerBucket) {
        if (samples.size() < minPerBucket * 2) return List.of();
        List<double[]> sorted = new ArrayList<>(samples);
        sorted.sort(Comparator.comparingDouble(s -> s[0]));
        int k = Math.max(2, Math.min(buckets, sorted.size() / minPerBucket));
        List<double[]> raw = new ArrayList<>(); // {medianBacklog, breaches, n}
        for (int b = 0; b < k; b++) {
            int from = b * sorted.size() / k, to = (b + 1) * sorted.size() / k;
            if (to <= from) continue;
            double breaches = 0;
            for (int i = from; i < to; i++) breaches += sorted.get(i)[1];
            raw.add(new double[]{sorted.get((from + to - 1) / 2)[0], breaches, to - from});
        }
        // pool adjacent violators on the smoothed rate
        List<double[]> pooled = new ArrayList<>();
        for (double[] r : raw) {
            pooled.add(r.clone());
            while (pooled.size() > 1 && rate(pooled.get(pooled.size() - 1)) < rate(pooled.get(pooled.size() - 2))) {
                double[] last = pooled.remove(pooled.size() - 1), prev = pooled.remove(pooled.size() - 1);
                pooled.add(new double[]{(prev[0] * prev[2] + last[0] * last[2]) / (prev[2] + last[2]), prev[1] + last[1], prev[2] + last[2]});
            }
        }
        List<Point> out = new ArrayList<>();
        for (double[] p : pooled) out.add(new Point(Math.round(p[0]), Math.round(rate(p) * 10_000) / 10_000d));
        return out;
    }

    public static List<String> encode(List<Point> curve) {
        return curve.stream().map(p -> String.format(java.util.Locale.ROOT, "%d:%.4f", Math.round(p.backlog()), p.breach())).toList();
    }

    private static double rate(double[] bucket) { return (bucket[1] + 1) / (bucket[2] + 2); }
}
