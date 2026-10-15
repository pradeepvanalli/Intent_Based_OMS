package ai.intentoms.analytics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Deploy-time settings of the analytics module (ARCHITECTURE §2 "Config", FR-ANL-01..03).
 *
 * @param enabled          false disables the scheduled nightly roll-up (tests call {@code KpiDailyRollup.rollupNow()} directly)
 * @param cacheTtlMs       dashboard cache per tenant + range; the control tower polls every 5 s, values rarely change; default 10000
 * @param liveWindowDays   days (ending today) always computed from the source tables; older days come from {@code kpi_daily}; default 30
 * @param maxTrendPoints   maximum points in a KPI trend series (longer ranges are bucketed); default 90
 * @param rollupCron       cron of the nightly roll-up (UTC); default 00:20
 * @param backfillDays     days (ending yesterday) recomputed by every roll-up so late events are picked up; default 7
 * @param exportBatch      rows fetched per round-trip by the NDJSON event export; default 1000
 * @param terminalStatuses header statuses that count as "reached a terminal state" (touchless rate) and close the open-orders gauge
 */
@ConfigurationProperties(prefix = "oms.analytics")
public record AnalyticsProperties(Boolean enabled, long cacheTtlMs, int liveWindowDays, int maxTrendPoints, String rollupCron, int backfillDays, int exportBatch,
                                  List<String> terminalStatuses) {
    public static final List<String> DEFAULT_TERMINAL = List.of("delivered", "cancelled", "picked_up", "refunded", "closed");

    public AnalyticsProperties {
        if (enabled == null) enabled = true;
        if (cacheTtlMs <= 0) cacheTtlMs = 10_000;
        if (liveWindowDays <= 0) liveWindowDays = 30;
        if (maxTrendPoints <= 0) maxTrendPoints = 90;
        if (rollupCron == null || rollupCron.isBlank()) rollupCron = "0 20 0 * * *";
        if (backfillDays <= 0) backfillDays = 7;
        if (exportBatch <= 0) exportBatch = 1_000;
        if (terminalStatuses == null || terminalStatuses.isEmpty()) terminalStatuses = DEFAULT_TERMINAL;
        terminalStatuses = List.copyOf(terminalStatuses);
    }

    public boolean isEnabled() { return enabled; }
}
