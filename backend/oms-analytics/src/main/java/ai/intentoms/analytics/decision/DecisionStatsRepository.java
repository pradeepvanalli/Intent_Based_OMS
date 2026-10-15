package ai.intentoms.analytics.decision;

import ai.intentoms.analytics.decision.DecisionAnalytics.AiCostRow;
import ai.intentoms.analytics.decision.DecisionAnalytics.AiCostDay;
import ai.intentoms.analytics.decision.DecisionAnalytics.DailyCount;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Windowed decision_record / ai_spend queries that complement {@code DecisionAnalyticsRepository} (which is all-time per version). FR-ANL-02, GR-17. */
@Repository
public class DecisionStatsRepository {
    private final JdbcClient jdbc;

    public DecisionStatsRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** Windowed totals of one decision over all versions. */
    public record WindowStats(String decision, long volume, long overrides, long escalations, double costUsd) {}

    /** Live records per version per UTC day since {@code from}. */
    public Map<Integer, List<DailyCount>> dailyByVersion(String decision, Instant from) {
        var rows = jdbc.sql("""
            select version, (created_at at time zone 'UTC')::date as day, count(*) as n from decision_record
            where tenant_id = :t and decision = :d and mode = 'LIVE' and created_at >= :from group by version, 2 order by version, 2
            """).param("t", TenantContext.require()).param("d", decision).param("from", Timestamp.from(from)).query().listOfRows();
        Map<Integer, List<DailyCount>> out = new LinkedHashMap<>();
        for (var r : rows) out.computeIfAbsent(((Number) r.get("version")).intValue(), v -> new ArrayList<>()).add(new DailyCount(((Date) r.get("day")).toLocalDate(), ((Number) r.get("n")).longValue()));
        return out;
    }

    /** Volume, overrides, escalations and cost per decision for live records since {@code from}. */
    public Map<String, WindowStats> overview(Instant from) {
        var rows = jdbc.sql("""
            select decision, count(*) as volume, count(*) filter (where overridden_at is not null) as overrides, count(*) filter (where requires_human) as escalations, coalesce(sum(cost_usd), 0) as cost
            from decision_record where tenant_id = :t and mode = 'LIVE' and created_at >= :from group by decision
            """).param("t", TenantContext.require()).param("from", Timestamp.from(from)).query().listOfRows();
        Map<String, WindowStats> out = new LinkedHashMap<>();
        for (var r : rows) {
            String d = (String) r.get("decision");
            out.put(d, new WindowStats(d, ((Number) r.get("volume")).longValue(), ((Number) r.get("overrides")).longValue(), ((Number) r.get("escalations")).longValue(), num(r.get("cost"))));
        }
        return out;
    }

    /** ai_spend per decision and provider for days in [from, to]. */
    public List<AiCostRow> spendByDecision(LocalDate from, LocalDate to) {
        var rows = jdbc.sql("""
            select decision, provider, sum(calls) as calls, sum(cost_usd) as cost, sum(input_tokens) as tokens from ai_spend
            where tenant_id = :t and day >= :from and day <= :to group by decision, provider order by cost desc, decision, provider
            """).param("t", TenantContext.require()).param("from", from).param("to", to).query().listOfRows();
        List<AiCostRow> out = new ArrayList<>();
        for (var r : rows) {
            long calls = ((Number) r.get("calls")).longValue();
            double cost = num(r.get("cost"));
            out.add(new AiCostRow((String) r.get("decision"), (String) r.get("provider"), calls, cost, ((Number) r.get("tokens")).longValue(), calls == 0 ? 0 : cost / calls));
        }
        return out;
    }

    /** ai_spend per day for days in [from, to]. */
    public List<AiCostDay> spendByDay(LocalDate from, LocalDate to) {
        var rows = jdbc.sql("select day, sum(cost_usd) as cost, sum(calls) as calls from ai_spend where tenant_id = :t and day >= :from and day <= :to group by day order by day")
            .param("t", TenantContext.require()).param("from", from).param("to", to).query().listOfRows();
        List<AiCostDay> out = new ArrayList<>();
        for (var r : rows) out.add(new AiCostDay(((Date) r.get("day")).toLocalDate(), num(r.get("cost")), ((Number) r.get("calls")).longValue()));
        return out;
    }

    private static double num(Object o) { return o instanceof Number n ? n.doubleValue() : 0.0; }
}
