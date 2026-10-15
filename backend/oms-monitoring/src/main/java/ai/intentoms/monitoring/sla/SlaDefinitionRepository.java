package ai.intentoms.monitoring.sla;

import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Persistence of {@code sla_definition} (FR-MON-02). */
@Repository
public class SlaDefinitionRepository {
    private static final RowMapper<SlaDefinition> MAPPER = (rs, i) -> new SlaDefinition(rs.getString("id"), rs.getString("order_type"), rs.getString("entity"), rs.getString("state"),
        rs.getString("max_age"), rs.getString("on_breach"), rs.getString("expected_next_event_within"), rs.getTimestamp("updated_at").toInstant());
    private static final String COLS = "id, order_type, entity, state, max_age, on_breach, expected_next_event_within, updated_at";
    private final JdbcClient jdbc;

    public SlaDefinitionRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public List<SlaDefinition> list() {
        return jdbc.sql("select " + COLS + " from sla_definition where tenant_id = :t order by order_type, entity, state").param("t", TenantContext.require()).query(MAPPER).list();
    }

    public Optional<SlaDefinition> find(String id) {
        return jdbc.sql("select " + COLS + " from sla_definition where tenant_id = :t and id = :id").param("t", TenantContext.require()).param("id", id).query(MAPPER).optional();
    }

    public Optional<SlaDefinition> find(String orderType, String entity, String state) {
        return jdbc.sql("select " + COLS + " from sla_definition where tenant_id = :t and order_type = :ot and entity = :e and state = :s")
            .param("t", TenantContext.require()).param("ot", orderType).param("e", entity).param("s", state).query(MAPPER).optional();
    }

    /** Rows for a state across entities, most specific order type first. */
    public List<SlaDefinition> forState(String orderType, String state) {
        return jdbc.sql("select " + COLS + " from sla_definition where tenant_id = :t and state = :s and order_type in (:ot, '*') order by case when order_type = :ot then 0 else 1 end, entity")
            .param("t", TenantContext.require()).param("s", state).param("ot", orderType == null ? "*" : orderType).query(MAPPER).list();
    }

    /** Insert or update on (orderType, entity, state); returns the stored row. */
    public SlaDefinition upsert(SlaDefinition d) {
        String id = d.id() == null || d.id().isBlank() ? Ids.prefixed("sla") : d.id();
        jdbc.sql("""
            insert into sla_definition(id, tenant_id, order_type, entity, state, max_age, on_breach, expected_next_event_within)
            values (:id, :t, :ot, :e, :s, :max, :ob, :exp)
            on conflict (tenant_id, order_type, entity, state) do update set max_age = excluded.max_age, on_breach = excluded.on_breach,
              expected_next_event_within = coalesce(excluded.expected_next_event_within, sla_definition.expected_next_event_within), updated_at = now()
            """).param("id", id).param("t", TenantContext.require()).param("ot", d.orderType()).param("e", d.entity()).param("s", d.state())
            .param("max", d.maxAge()).param("ob", d.onBreach()).param("exp", d.expectedNextEventWithin()).update();
        return find(d.orderType(), d.entity(), d.state()).orElseThrow();
    }

    public boolean delete(String id) {
        return jdbc.sql("delete from sla_definition where tenant_id = :t and id = :id").param("t", TenantContext.require()).param("id", id).update() > 0;
    }
}
