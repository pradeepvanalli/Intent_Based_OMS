package ai.intentoms.analytics.kpi;

import ai.intentoms.analytics.config.AnalyticsProperties;
import ai.intentoms.analytics.kpi.KpiCatalog.Kind;
import ai.intentoms.analytics.kpi.KpiCatalog.KpiDef;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.platform.time.TimeSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Nightly materialisation of the section 13 KPIs into {@code kpi_daily} (FR-ANL-01): the last {@code backfillDays} days
 * ending yesterday are recomputed from the source tables so late events are picked up, and the gauges (open orders, open
 * exceptions) are snapshotted for the day. The dashboard reads these rows for days older than the live window, which keeps
 * long-range trends cheap; the current day is always computed live.
 */
@Component
public class KpiDailyRollup {
    private static final Logger log = LoggerFactory.getLogger(KpiDailyRollup.class);
    private final KpiSeriesComputer computer;
    private final KpiDailyRepository daily;
    private final KpiRepository repo;
    private final KpiService kpis;
    private final TimeSource time;
    private final AnalyticsProperties props;

    public KpiDailyRollup(KpiSeriesComputer computer, KpiDailyRepository daily, KpiRepository repo, KpiService kpis, TimeSource time, AnalyticsProperties props) {
        this.computer = computer;
        this.daily = daily;
        this.repo = repo;
        this.kpis = kpis;
        this.time = time;
        this.props = props;
    }

    /** Every tenant with recent activity, shortly after midnight UTC. */
    @Scheduled(cron = "${oms.analytics.rollup-cron:0 20 0 * * *}", zone = "UTC")
    public void scheduled() {
        if (!props.isEnabled()) return;
        for (String tenant : repo.tenantsWithActivity(time.now().minus(Duration.ofDays(30)))) {
            try { TenantContext.runAs(tenant, this::rollupNow); }
            catch (RuntimeException e) { log.error("KPI roll-up failed for tenant {}", tenant, e); }
        }
    }

    /** Roll up the current tenant for yesterday (plus the backfill window). Returns rows written. */
    public int rollupNow() { return rollup(KpiRange.dayOf(time.now()).minusDays(1)); }

    /** Roll up the current tenant for {@code day} and the {@code backfillDays - 1} days before it; gauges are snapshotted for {@code day} only. */
    public int rollup(LocalDate day) {
        KpiRange range = new KpiRange(day.minusDays(props.backfillDays() - 1L), day);
        List<KpiDailyRepository.Row> rows = new ArrayList<>();
        for (String key : KpiCatalog.rollupKeys()) {
            KpiDef def = KpiCatalog.get(key);
            if (def.kind() == Kind.GAUGE) {
                double value = key.equals(KpiCatalog.OPEN_ORDERS) ? repo.openOrders() : repo.exceptionsOpen();
                rows.add(new KpiDailyRepository.Row(day, key, value, value, 1));
                continue;
            }
            for (DayPoint p : computer.live(key, range)) {
                if (range.contains(p.day())) rows.add(new KpiDailyRepository.Row(p.day(), key, p.hasData() ? p.value() : def.whenEmpty(), p.numerator(), p.denominator()));
            }
        }
        int written = daily.upsert(rows);
        kpis.invalidate();
        log.info("KPI roll-up for tenant {} wrote {} rows for {}", TenantContext.require(), written, range);
        return written;
    }
}
