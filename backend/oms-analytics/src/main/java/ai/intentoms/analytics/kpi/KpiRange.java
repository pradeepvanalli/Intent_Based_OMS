package ai.intentoms.analytics.kpi;

import ai.intentoms.platform.error.ValidationException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * Inclusive calendar-day range (UTC) a KPI dashboard is computed for (FR-ANL-01). Days are bucketed in UTC so that a point
 * in a trend series lines up with the {@code kpi_daily} roll-up, which uses the same convention.
 */
public record KpiRange(LocalDate from, LocalDate to) {
    public KpiRange {
        if (from == null || to == null) throw new ValidationException("from and to are required");
        if (from.isAfter(to)) throw new ValidationException("from must not be after to", Map.of("from", from.toString(), "to", to.toString()));
        if (ChronoUnit.DAYS.between(from, to) > 3660) throw new ValidationException("range must not exceed ten years");
    }

    /** The {@code n} days ending on {@code today} (inclusive). */
    public static KpiRange lastDays(LocalDate today, int n) { return new KpiRange(today.minusDays(Math.max(1, n) - 1L), today); }

    public static KpiRange single(LocalDate day) { return new KpiRange(day, day); }

    public static LocalDate dayOf(Instant instant) { return instant.atZone(ZoneOffset.UTC).toLocalDate(); }

    /** Number of calendar days covered (at least 1). */
    public int days() { return (int) ChronoUnit.DAYS.between(from, to) + 1; }

    /** The range of equal length ending the day before this one starts, used for deltas. */
    public KpiRange prior() { return new KpiRange(from.minusDays(days()), from.minusDays(1)); }

    /** This range plus the prior period, as one window so source tables are scanned once. */
    public KpiRange withPrior() { return new KpiRange(prior().from(), to); }

    public boolean contains(LocalDate day) { return !day.isBefore(from) && !day.isAfter(to); }

    public Instant startInclusive() { return from.atStartOfDay(ZoneOffset.UTC).toInstant(); }

    public Instant endExclusive() { return to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(); }

    @Override public String toString() { return from + "/" + to; }
}
