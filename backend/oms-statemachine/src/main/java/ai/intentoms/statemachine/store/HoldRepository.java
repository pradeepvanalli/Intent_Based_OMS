package ai.intentoms.statemachine.store;

import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.statemachine.model.Hold;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence of the hold overlay (FR-SM-07). */
@Repository
public class HoldRepository {
    private final JdbcClient jdbc;

    /** Hold plus its tenant (the sweeper works across tenants). */
    public record TenantHold(String tenantId, Hold hold) {}

    private static Hold map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Hold(rs.getString("id"), rs.getString("type"), rs.getString("scope_type"), rs.getString("scope_id"),
            Json.readSet(rs.getString("blocks")), rs.getString("reason_code"), rs.getString("note"), ts(rs.getTimestamp("expires_at")),
            rs.getString("placed_by"), ts(rs.getTimestamp("placed_at")), rs.getString("resolved_by"), ts(rs.getTimestamp("resolved_at")), rs.getString("resolution"));
    }

    private static final RowMapper<Hold> MAPPER = (rs, i) -> map(rs);
    private static final RowMapper<TenantHold> TENANT_MAPPER = (rs, i) -> new TenantHold(rs.getString("tenant_id"), map(rs));
    private static final String SELECT = "select id, tenant_id, type, scope_type, scope_id, blocks::text as blocks, reason_code, note, expires_at, placed_by, placed_at, resolved_by, resolved_at, resolution from hold";

    private static Instant ts(Timestamp t) { return t == null ? null : t.toInstant(); }

    public HoldRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void insert(Hold h) {
        jdbc.sql("""
            insert into hold(id, tenant_id, type, scope_type, scope_id, blocks, reason_code, note, expires_at, placed_by, placed_at)
            values (:id, :t, :type, :st, :sid, :b, :rc, :n, :exp, :by, :at)
            """)
            .param("id", h.id()).param("t", TenantContext.require()).param("type", h.type()).param("st", h.scopeType()).param("sid", h.scopeId())
            .param("b", Jsonb.of(h.blocksTransitions())).param("rc", h.reasonCode()).param("n", h.note())
            .param("exp", h.expiresAt() == null ? null : Timestamp.from(h.expiresAt())).param("by", h.placedBy()).param("at", Timestamp.from(h.placedAt()))
            .update();
    }

    public Optional<Hold> find(String id) {
        return jdbc.sql(SELECT + " where tenant_id = :t and id = :id").param("t", TenantContext.require()).param("id", id).query(MAPPER).optional();
    }

    public Optional<Hold> lock(String id) {
        return jdbc.sql(SELECT + " where tenant_id = :t and id = :id for update").param("t", TenantContext.require()).param("id", id).query(MAPPER).optional();
    }

    /** Unresolved and not yet expired holds on a scope. */
    public List<Hold> active(String scopeType, String scopeId, Instant now) {
        return jdbc.sql(SELECT + " where tenant_id = :t and scope_type = :st and scope_id = :sid and resolved_at is null and (expires_at is null or expires_at > :now) order by placed_at")
            .param("t", TenantContext.require()).param("st", scopeType).param("sid", scopeId).param("now", Timestamp.from(now)).query(MAPPER).list();
    }

    /** All holds on a scope including resolved ones (history view). */
    public List<Hold> all(String scopeType, String scopeId) {
        return jdbc.sql(SELECT + " where tenant_id = :t and scope_type = :st and scope_id = :sid order by placed_at")
            .param("t", TenantContext.require()).param("st", scopeType).param("sid", scopeId).query(MAPPER).list();
    }

    public void resolve(String id, String resolvedBy, Instant at, String resolution) {
        jdbc.sql("update hold set resolved_by = :by, resolved_at = :at, resolution = :r where tenant_id = :t and id = :id and resolved_at is null")
            .param("by", resolvedBy).param("at", Timestamp.from(at)).param("r", resolution).param("t", TenantContext.require()).param("id", id).update();
    }

    /** Expired, unresolved holds across all tenants, locked for the sweeper (skip rows another node is handling). */
    public List<TenantHold> expired(Instant now, int limit) {
        return jdbc.sql(SELECT + " where resolved_at is null and expires_at is not null and expires_at <= :now order by expires_at limit :l for update skip locked")
            .param("now", Timestamp.from(now)).param("l", limit).query(TENANT_MAPPER).list();
    }
}
