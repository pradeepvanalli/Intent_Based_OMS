package ai.intentoms.monitoring.sla;

import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Persistence of {@code order_risk} (FR-MON-02). */
@Repository
public class OrderRiskRepository {
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
    private static final RowMapper<OrderRisk> MAPPER = (rs, i) -> new OrderRisk(rs.getString("order_id"), rs.getInt("score"), rs.getString("level"),
        Json.read(rs.getString("reasons"), STRINGS), rs.getString("decision_record_id"), rs.getTimestamp("scored_at").toInstant());
    private final JdbcClient jdbc;

    public OrderRiskRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Optional<OrderRisk> find(String orderId) {
        return jdbc.sql("select order_id, score, level, reasons::text as reasons, decision_record_id, scored_at from order_risk where tenant_id = :t and order_id = :o")
            .param("t", TenantContext.require()).param("o", orderId).query(MAPPER).optional();
    }

    /** Stored risks for several orders in one round trip (order lists). */
    public Map<String, OrderRisk> findAll(java.util.Collection<String> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) return Map.of();
        Map<String, OrderRisk> out = new java.util.LinkedHashMap<>();
        jdbc.sql("select order_id, score, level, reasons::text as reasons, decision_record_id, scored_at from order_risk where tenant_id = :t and order_id in (:ids)")
            .param("t", TenantContext.require()).param("ids", List.copyOf(orderIds)).query(MAPPER).list().forEach(r -> out.put(r.orderId(), r));
        return out;
    }

    public void upsert(OrderRisk r) { upsert(r, null); }

    /** Store a freshly decided risk with the signature of the inputs it was decided on (null keeps the stored one). */
    public void upsert(OrderRisk r, String inputSignature) {
        jdbc.sql("""
            insert into order_risk(tenant_id, order_id, score, level, reasons, decision_record_id, scored_at, input_signature, checked_at)
            values (:t, :o, :s, :l, :r, :d, :at, :sig, :at)
            on conflict (tenant_id, order_id) do update set score = excluded.score, level = excluded.level, reasons = excluded.reasons,
              decision_record_id = excluded.decision_record_id, scored_at = excluded.scored_at,
              input_signature = coalesce(excluded.input_signature, order_risk.input_signature), checked_at = excluded.checked_at
            """).param("t", TenantContext.require()).param("o", r.orderId()).param("s", r.score()).param("l", r.level()).param("r", Jsonb.of(r.reasons()))
            .param("d", r.decisionRecordId()).param("at", Timestamp.from(r.scoredAt())).param("sig", inputSignature).update();
    }

    /** An order was examined but its material inputs had not changed: move it to the back of the sweep without a decision. */
    public void markChecked(String orderId, Instant at) {
        jdbc.sql("update order_risk set checked_at = :at where tenant_id = :t and order_id = :o")
            .param("at", Timestamp.from(at)).param("t", TenantContext.require()).param("o", orderId).update();
    }

    /**
     * Open orders of the current tenant due for (re)scoring, least recently scored first, with the state-entry time,
     * earliest promise date and the previous level.
     */
    public List<Map<String, Object>> openOrdersForScoring(List<String> terminalStatuses, String onlyOrderId, int limit) {
        String sql = """
            select o.id, o.status, o.order_type, o.channel, o.ship_method, o.primary_node_id, o.placed_at, o.grand_total, o.customer_ref,
                   coalesce(es.state, o.status) as state, coalesce(q.since, es.updated_at, o.placed_at) as since,
                   (select min(g.promise_date) from fulfilment_group g where g.tenant_id = o.tenant_id and g.order_id = o.id) as promise_date,
                   r.level as prev_level, r.scored_at, r.input_signature as prev_signature, r.score as prev_score,
                   r.reasons::text as prev_reasons, r.decision_record_id as prev_record_id
            from orders o
            left join entity_state es on es.tenant_id = o.tenant_id and es.entity_type = 'order' and es.entity_id = o.id
            left join entity_state_qty q on q.tenant_id = es.tenant_id and q.entity_type = es.entity_type and q.entity_id = es.entity_id and q.state = es.state
            left join order_risk r on r.tenant_id = o.tenant_id and r.order_id = o.id
            where o.tenant_id = :t and o.status not in (:terminal)
            """ + (onlyOrderId != null ? " and o.id = :oid" : "") + " order by coalesce(r.checked_at, r.scored_at) nulls first, o.placed_at limit :n";
        var spec = jdbc.sql(sql).param("t", TenantContext.require()).param("terminal", terminalStatuses).param("n", limit);
        if (onlyOrderId != null) spec = spec.param("oid", onlyOrderId);
        return spec.query().listOfRows();
    }

    /** Tenants that currently have open orders (sweeps run across tenants). */
    public List<String> tenantsWithOpenOrders(List<String> terminalStatuses) {
        return jdbc.sql("select distinct tenant_id from orders where status not in (:terminal)").param("terminal", terminalStatuses).query(String.class).list();
    }

    public static Instant ts(Object o) { return o == null ? null : ((Timestamp) o).toInstant(); }
}
