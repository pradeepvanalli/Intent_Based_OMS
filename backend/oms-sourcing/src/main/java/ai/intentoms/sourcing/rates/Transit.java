package ai.intentoms.sourcing.rates;

import ai.intentoms.sourcing.model.Node;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Cutoff and calendar arithmetic shared by candidate generation, plan building and promising (FR-SRC-01, FR-SRC-05). */
public final class Transit {
    private Transit() {}

    /** 1 when the node's local time is past its cutoff (the order ships tomorrow), else 0. */
    public static int cutoffDelayDays(Node node, Instant now) {
        if (node.cutoff() == null) return 0;
        LocalTime local = ZonedDateTime.ofInstant(now, zone(node)).toLocalTime();
        return local.isAfter(node.cutoff()) ? 1 : 0;
    }

    /** Minutes until the node's cutoff today (negative when past it) — a {@code sourcing_node_score} input. */
    public static long minutesToCutoff(Node node, Instant now) {
        if (node.cutoff() == null) return 24 * 60;
        ZonedDateTime local = ZonedDateTime.ofInstant(now, zone(node));
        ZonedDateTime cutoff = local.with(node.cutoff());
        return java.time.Duration.between(local, cutoff).toMinutes();
    }

    /** Today's date in the node's zone. */
    public static LocalDate today(Node node, Instant now) { return ZonedDateTime.ofInstant(now, zone(node)).toLocalDate(); }

    /** Adds {@code days} to {@code from}; with {@code businessDaysOnly} Saturdays and Sundays are skipped. */
    public static LocalDate eta(LocalDate from, int days, boolean businessDaysOnly) {
        if (!businessDaysOnly) return from.plusDays(Math.max(0, days));
        LocalDate d = from;
        int remaining = Math.max(0, days);
        while (remaining > 0) {
            d = d.plusDays(1);
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) remaining--;
        }
        if (days == 0) while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) d = d.plusDays(1);
        return d;
    }

    public static ZoneId zone(Node node) {
        try { return ZoneId.of(node.timezone()); } catch (RuntimeException e) { return ZoneId.of("UTC"); }
    }
}
