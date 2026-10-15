package ai.intentoms.analytics.economics;

import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Windowed {@code decision_record} aggregates for the AI economics report. Records are joined to {@code decision_definition}
 * on (name, version) for the tier; only {@code mode = 'LIVE'} rows count. Windows are {@code [from, to)} instants.
 */
@Repository
public class AiEconomicsRepository {
    private static final String WINDOW = " r.tenant_id = :t and r.mode = 'LIVE' and r.created_at >= :from and r.created_at < :to ";
    private static final String JOIN = " from decision_record r left join decision_definition dd on dd.tenant_id = r.tenant_id and dd.name = r.decision and dd.version = r.version where ";
    private final JdbcClient jdbc;

    public AiEconomicsRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public record Totals(long volume, long acting, long avoided, long overrides, long escalations, double costUsd) {}
    public record LayerRow(String layer, long count, double p50, double p95) {}
    public record DayLayerRow(LocalDate day, String layer, long count) {}
    public record DecisionAgg(String decision, String tier, long volume, long acting, double avgConfidence, double costUsd, double p95, long overrides) {}

    public Totals totals(Instant from, Instant to) {
        var r = jdbc.sql("select count(*) as volume, count(*) filter (where r.action = 'act') as acting, "
                + "count(*) filter (where r.action = 'act' and dd.tier in ('T1','T2')) as avoided, "
                + "count(*) filter (where r.overridden_at is not null) as overrides, count(*) filter (where r.requires_human) as escalations, "
                + "coalesce(sum(r.cost_usd), 0) as cost" + JOIN + WINDOW)
            .param("t", TenantContext.require()).param("from", Timestamp.from(from)).param("to", Timestamp.from(to)).query().singleRow();
        return new Totals(lng(r.get("volume")), lng(r.get("acting")), lng(r.get("avoided")), lng(r.get("overrides")), lng(r.get("escalations")), dbl(r.get("cost")));
    }

    public List<LayerRow> byLayer(Instant from, Instant to) {
        var rows = jdbc.sql("select r.layer, count(*) as n, percentile_cont(0.5) within group (order by r.latency_ms) as p50, "
                + "percentile_cont(0.95) within group (order by r.latency_ms) as p95 from decision_record r where" + WINDOW + "group by r.layer")
            .param("t", TenantContext.require()).param("from", Timestamp.from(from)).param("to", Timestamp.from(to)).query().listOfRows();
        List<LayerRow> out = new ArrayList<>();
        for (var r : rows) out.add(new LayerRow((String) r.get("layer"), lng(r.get("n")), dbl(r.get("p50")), dbl(r.get("p95"))));
        return out;
    }

    public List<DayLayerRow> dailyByLayer(Instant from, Instant to) {
        var rows = jdbc.sql("select (r.created_at at time zone 'UTC')::date as day, r.layer, count(*) as n from decision_record r where" + WINDOW + "group by 1, 2 order by 1")
            .param("t", TenantContext.require()).param("from", Timestamp.from(from)).param("to", Timestamp.from(to)).query().listOfRows();
        List<DayLayerRow> out = new ArrayList<>();
        for (var r : rows) out.add(new DayLayerRow(((Date) r.get("day")).toLocalDate(), (String) r.get("layer"), lng(r.get("n"))));
        return out;
    }

    public List<DecisionAgg> byDecision(Instant from, Instant to) {
        var rows = jdbc.sql("select r.decision, max(dd.tier) as tier, count(*) as volume, count(*) filter (where r.action = 'act') as acting, "
                + "avg(r.confidence) as conf, coalesce(sum(r.cost_usd), 0) as cost, percentile_cont(0.95) within group (order by r.latency_ms) as p95, "
                + "count(*) filter (where r.overridden_at is not null) as overrides" + JOIN + WINDOW + "group by r.decision order by volume desc, r.decision")
            .param("t", TenantContext.require()).param("from", Timestamp.from(from)).param("to", Timestamp.from(to)).query().listOfRows();
        List<DecisionAgg> out = new ArrayList<>();
        for (var r : rows) out.add(new DecisionAgg((String) r.get("decision"), (String) r.get("tier"), lng(r.get("volume")), lng(r.get("acting")), dbl(r.get("conf")), dbl(r.get("cost")), dbl(r.get("p95")), lng(r.get("overrides"))));
        return out;
    }

    /** Golden-set accuracy of each decision's live version, when an evaluation report exists. */
    public Map<String, Double> goldenAccuracyByDecision() {
        var rows = jdbc.sql("select name, (evaluation_report->>'accuracy')::double precision as acc from decision_definition "
                + "where tenant_id = :t and status = 'live' and evaluation_report is not null and evaluation_report->>'accuracy' is not null")
            .param("t", TenantContext.require()).query().listOfRows();
        Map<String, Double> out = new HashMap<>();
        for (var r : rows) out.put((String) r.get("name"), dbl(r.get("acc")));
        return out;
    }

    public long ordersPlaced(Instant from, Instant to) {
        return jdbc.sql("select count(*) from orders where tenant_id = :t and placed_at >= :from and placed_at < :to")
            .param("t", TenantContext.require()).param("from", Timestamp.from(from)).param("to", Timestamp.from(to)).query(Long.class).single();
    }

    static Map<String, Long> emptyLayers(String[] layers) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (String l : layers) m.put(l, 0L);
        return m;
    }

    private static long lng(Object o) { return o instanceof Number n ? n.longValue() : 0L; }
    private static double dbl(Object o) { return o instanceof Number n ? n.doubleValue() : 0.0; }
}
