package ai.intentoms.decision.analytics;

import ai.intentoms.decision.model.EvaluationReport.CalibrationBin;
import ai.intentoms.decision.studio.Calibration;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decision analytics query helpers for oms-analytics (FR-ANL-02): per version volume, band and layer mix, override rate,
 * calibration curve from labelled records, latency p50/p95, cost and touchless share; plus a daily series.
 */
@Repository
public class DecisionAnalyticsRepository {
    private final JdbcClient jdbc;

    public DecisionAnalyticsRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public record VersionStats(int version, long volume, Map<String, Long> bandMix, Map<String, Long> layerMix, double overrideRate, List<CalibrationBin> calibration, double calibrationError,
                               double latencyP50Ms, double latencyP95Ms, double costTotalUsd, double costAvgUsd, double touchlessShare, long escalations, Instant firstSeen, Instant lastSeen) {}

    public record DailyPoint(LocalDate day, long volume, double costUsd, long escalations, long overrides, double touchlessShare, double avgLatencyMs) {}

    public List<VersionStats> perVersion(String decision) {
        String tenant = TenantContext.require();
        var rows = jdbc.sql("""
            select version, count(*) as volume,
                   count(*) filter (where overridden_at is not null) as overrides,
                   count(*) filter (where action = 'act' and not requires_human) as touchless,
                   count(*) filter (where requires_human) as escalations,
                   percentile_cont(0.5) within group (order by latency_ms) as p50,
                   percentile_cont(0.95) within group (order by latency_ms) as p95,
                   sum(cost_usd) as cost, min(created_at) as first_seen, max(created_at) as last_seen
            from decision_record where tenant_id = :t and decision = :d and mode = 'LIVE' group by version order by version desc
            """).param("t", tenant).param("d", decision).query().listOfRows();
        List<VersionStats> out = new ArrayList<>();
        for (var r : rows) {
            int version = ((Number) r.get("version")).intValue();
            long volume = ((Number) r.get("volume")).longValue();
            Map<String, Long> bands = mix("band", decision, version);
            Map<String, Long> layers = mix("layer", decision, version);
            Calibration.Result cal = calibration(decision, version);
            out.add(new VersionStats(version, volume, bands, layers, volume == 0 ? 0 : num(r.get("overrides")) / volume, cal.bins(), cal.error(), num(r.get("p50")), num(r.get("p95")),
                num(r.get("cost")), volume == 0 ? 0 : num(r.get("cost")) / volume, volume == 0 ? 0 : num(r.get("touchless")) / volume, ((Number) r.get("escalations")).longValue(),
                ((Timestamp) r.get("first_seen")).toInstant(), ((Timestamp) r.get("last_seen")).toInstant()));
        }
        return out;
    }

    private Map<String, Long> mix(String column, String decision, int version) {
        var rows = jdbc.sql("select " + column + " as k, count(*) as n from decision_record where tenant_id = :t and decision = :d and version = :v and mode = 'LIVE' group by " + column)
            .param("t", TenantContext.require()).param("d", decision).param("v", version).query().listOfRows();
        Map<String, Long> out = new LinkedHashMap<>();
        rows.forEach(r -> out.put((String) r.get("k"), ((Number) r.get("n")).longValue()));
        return out;
    }

    /** Calibration curve from labelled LIVE records of one version (labels correct/outcome, overrides count as wrong). */
    public Calibration.Result calibration(String decision, int version) {
        var rows = jdbc.sql("""
            select confidence, value::text as value, labels::text as labels, overridden_at from decision_record
            where tenant_id = :t and decision = :d and version = :v and mode = 'LIVE' and (jsonb_exists(labels, 'correct') or jsonb_exists(labels, 'outcome') or overridden_at is not null)
            """).param("t", TenantContext.require()).param("d", decision).param("v", version).query().listOfRows();
        List<Calibration.Observation> obs = new ArrayList<>();
        for (var r : rows) {
            Map<String, Object> labels = ai.intentoms.platform.json.Json.readMap((String) r.get("labels"));
            Object value = r.get("value") == null ? null : ai.intentoms.platform.json.Json.read((String) r.get("value"), Object.class);
            boolean correct;
            if (r.get("overridden_at") != null) correct = false;
            else if (labels.get("correct") != null) { Object c = labels.get("correct"); Object v = c instanceof Map<?, ?> m ? m.get("value") : c; correct = v instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(v)); }
            else { Object o = labels.get("outcome"); Object v = o instanceof Map<?, ?> m ? m.get("value") : o; correct = Calibration.valueEquals(v, value); }
            obs.add(new Calibration.Observation(num(r.get("confidence")), correct));
        }
        return Calibration.compute(obs);
    }

    public List<DailyPoint> daily(String decision, int days) {
        var rows = jdbc.sql("""
            select date_trunc('day', created_at)::date as day, count(*) as volume, sum(cost_usd) as cost,
                   count(*) filter (where requires_human) as escalations, count(*) filter (where overridden_at is not null) as overrides,
                   count(*) filter (where action = 'act' and not requires_human) as touchless, avg(latency_ms) as lat
            from decision_record where tenant_id = :t and decision = :d and mode = 'LIVE' and created_at >= now() - make_interval(days => :days)
            group by 1 order by 1
            """).param("t", TenantContext.require()).param("d", decision).param("days", Math.max(1, days)).query().listOfRows();
        List<DailyPoint> out = new ArrayList<>();
        for (var r : rows) {
            long volume = ((Number) r.get("volume")).longValue();
            out.add(new DailyPoint(((java.sql.Date) r.get("day")).toLocalDate(), volume, num(r.get("cost")), ((Number) r.get("escalations")).longValue(), ((Number) r.get("overrides")).longValue(),
                volume == 0 ? 0 : num(r.get("touchless")) / volume, num(r.get("lat"))));
        }
        return out;
    }

    /** AI cost per decision over a window (GR-17 KPI input). */
    public Map<String, Double> costByDecision(Instant from, Instant to) {
        var rows = jdbc.sql("select decision, sum(cost_usd) as cost from decision_record where tenant_id = :t and created_at >= :f and created_at < :to group by decision order by cost desc")
            .param("t", TenantContext.require()).param("f", Timestamp.from(from)).param("to", Timestamp.from(to)).query().listOfRows();
        Map<String, Double> out = new LinkedHashMap<>();
        rows.forEach(r -> out.put((String) r.get("decision"), num(r.get("cost"))));
        return out;
    }

    private static double num(Object o) { return o instanceof Number n ? n.doubleValue() : 0.0; }
}
