package ai.intentoms.learning.model;

import ai.intentoms.platform.config.TenantConfigStore;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * FS-02: the one day-part definition. Night (00-06), morning (06-12), afternoon (12-18) and evening (18-24) on the
 * tenant's clock ({@code tenant.timezone}, an IANA zone such as {@code America/New_York}; UTC when unset or invalid).
 * The node-reliability learner files outcomes under it, and the live lookups ({@code SoftScorer} stamping
 * {@code order.day_part}, {@code NodeReliabilityFeatures} reading the facts, the {@code order.sourced} package stamp)
 * all use it, so a decision reads the facts of the same day part the learner wrote them under.
 */
public final class DayPart {
    private DayPart() {}

    /** Tenant config key holding the tenant's IANA time zone. */
    public static final String TIMEZONE_KEY = "tenant.timezone";

    /** The tenant's time zone, UTC when unset or invalid. */
    public static ZoneId zone(TenantConfigStore config) {
        try { return ZoneId.of(config.getString(TIMEZONE_KEY, "UTC")); }
        catch (RuntimeException e) { return ZoneOffset.UTC; }
    }

    /** The day part of an instant on the tenant's clock. */
    public static String of(Instant at, TenantConfigStore config) { return of(at, zone(config)); }

    /** The day part of an instant in a zone. */
    public static String of(Instant at, ZoneId zone) {
        int hour = at.atZone(zone).getHour();
        if (hour < 6) return "night";
        if (hour < 12) return "morning";
        if (hour < 18) return "afternoon";
        return "evening";
    }
}
