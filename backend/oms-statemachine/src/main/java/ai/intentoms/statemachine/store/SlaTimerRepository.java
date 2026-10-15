package ai.intentoms.statemachine.store;

import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.statemachine.model.SlaTimer;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** Persistence of SLA timers (FR-SM-08). */
@Repository
public class SlaTimerRepository {
    private final JdbcClient jdbc;

    private static final RowMapper<SlaTimer> MAPPER = (rs, i) -> new SlaTimer(rs.getString("id"), rs.getString("tenant_id"), rs.getString("entity_type"),
        rs.getString("entity_id"), rs.getString("state"), rs.getTimestamp("entered_at").toInstant(), rs.getTimestamp("due_at").toInstant(), rs.getString("on_breach"),
        ts(rs.getTimestamp("fired_at")), ts(rs.getTimestamp("cancelled_at")));

    private static Instant ts(Timestamp t) { return t == null ? null : t.toInstant(); }

    public SlaTimerRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void insert(SlaTimer t) {
        jdbc.sql("insert into sla_timer(id, tenant_id, entity_type, entity_id, state, entered_at, due_at, on_breach) values (:id, :t, :et, :eid, :s, :ent, :due, :ob)")
            .param("id", t.id()).param("t", TenantContext.require()).param("et", t.entityType()).param("eid", t.entityId()).param("s", t.state())
            .param("ent", Timestamp.from(t.enteredAt())).param("due", Timestamp.from(t.dueAt())).param("ob", t.onBreach()).update();
    }

    /** Open (not fired, not cancelled) timers for an entity, optionally for one state. */
    public List<SlaTimer> open(String entityType, String entityId, String state) {
        var q = jdbc.sql("select * from sla_timer where tenant_id = :t and entity_type = :et and entity_id = :eid and fired_at is null and cancelled_at is null"
                + (state == null ? "" : " and state = :s") + " order by due_at")
            .param("t", TenantContext.require()).param("et", entityType).param("eid", entityId);
        if (state != null) q = q.param("s", state);
        return q.query(MAPPER).list();
    }

    public int cancel(String entityType, String entityId, String state, Instant at) {
        return jdbc.sql("update sla_timer set cancelled_at = :at where tenant_id = :t and entity_type = :et and entity_id = :eid and state = :s and fired_at is null and cancelled_at is null")
            .param("at", Timestamp.from(at)).param("t", TenantContext.require()).param("et", entityType).param("eid", entityId).param("s", state).update();
    }

    public void markFired(String id, Instant at) {
        jdbc.sql("update sla_timer set fired_at = :at where id = :id and fired_at is null").param("at", Timestamp.from(at)).param("id", id).update();
    }

    /** Due, still-open timers across all tenants (candidates for the sweeper; each is re-checked under {@link #lockOpen}). */
    public List<SlaTimer> due(Instant now, int limit) {
        return jdbc.sql("select * from sla_timer where fired_at is null and cancelled_at is null and due_at <= :now order by due_at limit :l")
            .param("now", Timestamp.from(now)).param("l", limit).query(MAPPER).list();
    }

    /** Lock one open timer for firing; empty when it was fired/cancelled meanwhile or another node holds it. */
    public java.util.Optional<SlaTimer> lockOpen(String id) {
        return jdbc.sql("select * from sla_timer where id = :id and fired_at is null and cancelled_at is null for update skip locked")
            .param("id", id).query(MAPPER).optional();
    }

    public List<SlaTimer> history(String entityType, String entityId) {
        return jdbc.sql("select * from sla_timer where tenant_id = :t and entity_type = :et and entity_id = :eid order by entered_at")
            .param("t", TenantContext.require()).param("et", entityType).param("eid", entityId).query(MAPPER).list();
    }
}
