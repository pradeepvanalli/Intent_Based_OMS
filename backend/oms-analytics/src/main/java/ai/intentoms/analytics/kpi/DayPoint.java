package ai.intentoms.analytics.kpi;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * One day of a KPI series as a fraction so points can be merged exactly: ratio KPIs carry counts (numerator / denominator),
 * duration KPIs carry {@code median x count / count}, gauges carry {@code value / 1}. {@link #value()} is the day's KPI value.
 */
public record DayPoint(LocalDate day, double numerator, double denominator) {
    public double value() { return KpiMath.rate(numerator, denominator); }

    public boolean hasData() { return denominator > 0; }

    /** Join a numerator map and a denominator map on day (union of days). */
    public static List<DayPoint> merge(Map<LocalDate, Double> numerators, Map<LocalDate, Double> denominators) {
        TreeSet<LocalDate> days = new TreeSet<>(numerators.keySet());
        days.addAll(denominators.keySet());
        List<DayPoint> out = new ArrayList<>(days.size());
        for (LocalDate d : days) out.add(new DayPoint(d, numerators.getOrDefault(d, 0.0), denominators.getOrDefault(d, 0.0)));
        return out;
    }

    /** Scale the numerators (e.g. x100 for "per 100 orders", x1000 for "per $1,000 refunded"). */
    public static List<DayPoint> scaleNumerator(List<DayPoint> points, double factor) {
        List<DayPoint> out = new ArrayList<>(points.size());
        for (DayPoint p : points) out.add(new DayPoint(p.day(), p.numerator() * factor, p.denominator()));
        return out;
    }
}
