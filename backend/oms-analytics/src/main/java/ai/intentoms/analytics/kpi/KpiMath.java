package ai.intentoms.analytics.kpi;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.OptionalDouble;
import java.util.TreeMap;

/**
 * Pure KPI arithmetic shared by the live dashboard and the nightly roll-up (FR-ANL-01, section 13): rates with empty
 * denominators, medians, baseline-relative targets ("-15% vs baseline"), period-over-period deltas and trend shaping.
 */
public final class KpiMath {
    private KpiMath() {}

    /** numerator / denominator, or 0 when the denominator is empty. */
    public static double rate(double numerator, double denominator) { return denominator <= 0 ? 0.0 : numerator / denominator; }

    /** numerator / denominator, or {@code whenEmpty} when nothing was measured (e.g. margin-floor compliance is 100% with no below-floor orders). */
    public static double rate(double numerator, double denominator, double whenEmpty) { return denominator <= 0 ? whenEmpty : numerator / denominator; }

    /** Aggregate value of a series: sum of numerators over sum of denominators (exact for ratio KPIs, count-weighted for medians). */
    public static double value(Collection<DayPoint> points) {
        double n = 0, d = 0;
        for (DayPoint p : points) { n += p.numerator(); d += p.denominator(); }
        return rate(n, d);
    }

    public static double denominator(Collection<DayPoint> points) {
        double d = 0;
        for (DayPoint p : points) d += p.denominator();
        return d;
    }

    /** Median of the sample (average of the two middle values for even sizes); empty for an empty sample. */
    public static OptionalDouble median(Collection<Double> sample) {
        if (sample == null || sample.isEmpty()) return OptionalDouble.empty();
        double[] a = sample.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        int mid = a.length / 2;
        return OptionalDouble.of(a.length % 2 == 1 ? a[mid] : (a[mid - 1] + a[mid]) / 2.0);
    }

    /**
     * Section 13 "x% vs baseline" target: {@code baseline x (1 + pctChange)}, e.g. a baseline split rate of 0.20 and -15%
     * gives 0.17. Null when the tenant has not recorded a baseline (the KPI is then shown without a target).
     */
    public static Double targetFromBaseline(Double baseline, double pctChange) {
        if (baseline == null) return null;
        return round(baseline * (1.0 + pctChange), 6);
    }

    /** current - prior, or null when either side is unknown. */
    public static Double delta(Double current, Double prior) {
        if (current == null || prior == null) return null;
        return round(current - prior, 6);
    }

    public static double round(double v, int decimals) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        double f = Math.pow(10, decimals);
        return Math.round(v * f) / f;
    }

    /**
     * Daily trend over {@code range}: one value per day, gaps filled by carrying the last known value forward (and the first
     * known value backward) so a day without orders does not render as a drop to zero; bucketed to at most {@code maxPoints}
     * by merging consecutive days exactly (sum of fractions).
     */
    public static List<Double> trend(List<DayPoint> points, KpiRange range, int maxPoints, double whenEmpty) {
        TreeMap<LocalDate, DayPoint> byDay = new TreeMap<>();
        for (DayPoint p : points) if (range.contains(p.day())) byDay.merge(p.day(), p, (a, b) -> new DayPoint(a.day(), a.numerator() + b.numerator(), a.denominator() + b.denominator()));
        int days = range.days();
        int buckets = Math.min(Math.max(1, maxPoints), days);
        List<Double> out = new ArrayList<>(buckets);
        Double last = null;
        for (int b = 0; b < buckets; b++) {
            long startOffset = (long) days * b / buckets, endOffset = (long) days * (b + 1) / buckets;
            double n = 0, d = 0;
            for (long o = startOffset; o < endOffset; o++) {
                DayPoint p = byDay.get(range.from().plusDays(o));
                if (p != null) { n += p.numerator(); d += p.denominator(); }
            }
            if (d > 0) { last = n / d; out.add(round(last, 6)); } else out.add(null);
        }
        Double fill = null;
        for (Double v : out) if (v != null) { fill = v; break; }
        if (fill == null) fill = whenEmpty;
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i) == null) out.set(i, fill); else fill = out.get(i);
        }
        return out;
    }

    /** Points of {@code points} whose day falls inside {@code range}. */
    public static List<DayPoint> within(List<DayPoint> points, KpiRange range) {
        List<DayPoint> out = new ArrayList<>();
        for (DayPoint p : points) if (range.contains(p.day())) out.add(p);
        return out;
    }
}
