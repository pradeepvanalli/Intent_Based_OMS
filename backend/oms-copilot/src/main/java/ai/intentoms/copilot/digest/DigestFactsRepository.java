package ai.intentoms.copilot.digest;

import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compact, tenant-scoped fact set for the Margin Guardian digest, computed directly over orders, fulfilment groups,
 * decision records, returns, exceptions and the AI usage ledgers for a period and the period before it (deltas).
 */
@Repository
public class DigestFactsRepository {
    private final JdbcClient jdbc;
    private final TenantConfigStore config;

    public DigestFactsRepository(JdbcClient jdbc, TenantConfigStore config) {
        this.jdbc = jdbc;
        this.config = config;
    }

    public Map<String, Object> facts(Instant from, Instant to) {
        String t = TenantContext.require();
        Map<String, Object> f = new LinkedHashMap<>();
        Instant prevFrom = from.minus(java.time.Duration.between(from, to));
        f.put("period", Map.of("from", from.toString(), "to", to.toString()));
        f.put("orders", orders(t, from, to));
        f.put("previousOrders", orders(t, prevFrom, from));
        double penalty = config.getDouble("sourcing.split_penalty_usd", 12.0);
        f.put("splitPenaltyUsd", penalty);
        f.put("splits", splits(t, from, to, penalty));
        f.put("previousSplits", splits(t, prevFrom, from, penalty));
        f.put("marginFloor", config.getDouble("sourcing.margin_floor", 0.12));
        f.put("belowFloor", belowFloor(t, from, to));
        f.put("returnsByTiming", returns(t, from, to));
        f.put("topExceptions", exceptions(t, from, to));
        f.put("decisions", decisions(t, from, to));
        f.put("aiCost", aiCost(t, from, to));
        return f;
    }

    private Map<String, Object> orders(String t, Instant from, Instant to) {
        Map<String, Object> r = jdbc.sql("select count(*) as orders, round(coalesce(sum(grand_total), 0), 2) as revenue, round(coalesce(avg(margin_ratio), 0)::numeric, 4) as avg_margin_ratio, count(*) filter (where agent_order) as agent_orders from orders where tenant_id = :t and placed_at >= :f and placed_at < :to")
            .param("t", t).param("f", Timestamp.from(from)).param("to", Timestamp.from(to)).query().singleRow();
        return clean(r);
    }

    private Map<String, Object> splits(String t, Instant from, Instant to, double penalty) {
        Map<String, Object> r = jdbc.sql("""
            select count(*) as sourced_orders, count(*) filter (where g.packages > 1) as split_orders, coalesce(sum(g.packages - 1), 0) as extra_packages,
                   round(100.0 * count(*) filter (where g.packages > 1) / greatest(count(*), 1), 1) as split_rate_pct
            from orders o join (select order_id, count(*) as packages from fulfilment_group where tenant_id = :t and status not in ('rejected','cancelled') group by order_id) g on g.order_id = o.id
            where o.tenant_id = :t and o.placed_at >= :f and o.placed_at < :to""").param("t", t).param("f", Timestamp.from(from)).param("to", Timestamp.from(to)).query().singleRow();
        Map<String, Object> m = clean(r);
        long extra = ((Number) m.getOrDefault("extra_packages", 0)).longValue();
        m.put("estimated_split_cost_usd", BigDecimal.valueOf(extra * penalty).setScale(2, RoundingMode.HALF_UP));
        List<Map<String, Object>> byChannel = jdbc.sql("""
            select o.channel, count(*) as orders, count(*) filter (where g.packages > 1) as split_orders, round(100.0 * count(*) filter (where g.packages > 1) / greatest(count(*), 1), 1) as split_rate_pct
            from orders o join (select order_id, count(*) as packages from fulfilment_group where tenant_id = :t and status not in ('rejected','cancelled') group by order_id) g on g.order_id = o.id
            where o.tenant_id = :t and o.placed_at >= :f and o.placed_at < :to group by o.channel order by split_orders desc""").param("t", t).param("f", Timestamp.from(from)).param("to", Timestamp.from(to)).query().listOfRows();
        m.put("by_channel", byChannel.stream().map(DigestFactsRepository::clean).toList());
        return m;
    }

    private Map<String, Object> belowFloor(String t, Instant from, Instant to) {
        Map<String, Object> r = jdbc.sql("""
            select count(*) as below_floor_orders, count(*) filter (where coalesce((margin->>'approvalPresent')::boolean, false)) as approved,
                   round(coalesce(sum(greatest((margin->>'floor')::numeric - margin_ratio::numeric, 0) * grand_total), 0), 2) as margin_given_up_usd,
                   round(coalesce(sum(grand_total), 0), 2) as revenue
            from orders where tenant_id = :t and below_floor = true and placed_at >= :f and placed_at < :to""").param("t", t).param("f", Timestamp.from(from)).param("to", Timestamp.from(to)).query().singleRow();
        return clean(r);
    }

    private List<Map<String, Object>> returns(String t, Instant from, Instant to) {
        return jdbc.sql("select coalesce(refund_timing, 'undecided') as refund_timing, count(*) as returns, round(coalesce(sum((refund->>'amount')::numeric), 0), 2) as refund_value_usd, round(coalesce(avg(risk_score), 0)::numeric, 2) as avg_risk_score from return_rma where tenant_id = :t and created_at >= :f and created_at < :to group by refund_timing order by returns desc")
            .param("t", t).param("f", Timestamp.from(from)).param("to", Timestamp.from(to)).query().listOfRows().stream().map(DigestFactsRepository::clean).toList();
    }

    private List<Map<String, Object>> exceptions(String t, Instant from, Instant to) {
        return jdbc.sql("select type, count(*) as exceptions, count(*) filter (where status = 'OPEN') as open from exception where tenant_id = :t and created_at >= :f and created_at < :to group by type order by exceptions desc limit 5")
            .param("t", t).param("f", Timestamp.from(from)).param("to", Timestamp.from(to)).query().listOfRows().stream().map(DigestFactsRepository::clean).toList();
    }

    private List<Map<String, Object>> decisions(String t, Instant from, Instant to) {
        return jdbc.sql("""
            select decision, count(*) as volume, count(*) filter (where requires_human or band = 'ESCALATE') as escalations, count(*) filter (where overridden_at is not null) as overrides,
                   round(100.0 * count(*) filter (where requires_human or band = 'ESCALATE') / greatest(count(*), 1), 1) as escalation_rate_pct,
                   round(100.0 * count(*) filter (where overridden_at is not null) / greatest(count(*), 1), 1) as override_rate_pct, round(coalesce(sum(cost_usd), 0)::numeric, 4) as cost_usd
            from decision_record where tenant_id = :t and mode = 'LIVE' and created_at >= :f and created_at < :to group by decision order by volume desc limit 10""")
            .param("t", t).param("f", Timestamp.from(from)).param("to", Timestamp.from(to)).query().listOfRows().stream().map(DigestFactsRepository::clean).toList();
    }

    private Map<String, Object> aiCost(String t, Instant from, Instant to) {
        double decisionsCost = jdbc.sql("select coalesce(sum(cost_usd), 0) from decision_record where tenant_id = :t and created_at >= :f and created_at < :to").param("t", t).param("f", Timestamp.from(from)).param("to", Timestamp.from(to)).query(Double.class).single();
        double copilotCost = jdbc.sql("select coalesce(sum(cost_usd), 0) from copilot_usage where tenant_id = :t and at >= :f and at < :to").param("t", t).param("f", Timestamp.from(from)).param("to", Timestamp.from(to)).query(Double.class).single();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("decisions_usd", round4(decisionsCost));
        m.put("copilot_usd", round4(copilotCost));
        m.put("total_usd", round4(decisionsCost + copilotCost));
        m.put("budget_usd_per_day", config.getDouble("ai.budget_usd_per_day", -1));
        return m;
    }

    private static Map<String, Object> clean(Map<String, Object> r) {
        Map<String, Object> out = new LinkedHashMap<>();
        r.forEach((k, v) -> out.put(k, v instanceof BigDecimal b ? b.stripTrailingZeros().toPlainString().contains(".") ? b : b.setScale(0, RoundingMode.UNNECESSARY) : v));
        return out;
    }

    private static BigDecimal round4(double d) { return BigDecimal.valueOf(d).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros(); }
}
