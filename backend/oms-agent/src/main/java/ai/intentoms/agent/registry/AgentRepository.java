package ai.intentoms.agent.registry;

import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** JDBC access to {@code agent_identity} and {@code agent_activity}; every query is tenant-scoped (ARCHITECTURE §2). */
@Repository
public class AgentRepository {
    private final JdbcClient jdbc;

    public AgentRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** Full row including the encrypted secret; never leaves the registry package. */
    public record Row(String tenantId, String agentId, String owner, String protocol, int trustTier, String secretHash, String secretEnc, String publicKey,
                      String apiKeyId, AgentLimits limits, String callbackUrl, String status, Instant createdAt, Instant updatedAt, Instant lastSeenAt) {
        public boolean active() { return "active".equals(status); }
    }

    private static final RowMapper<Row> ROW = (rs, i) -> new Row(rs.getString("tenant_id"), rs.getString("agent_id"), rs.getString("owner"), rs.getString("protocol"),
        rs.getInt("trust_tier"), rs.getString("secret_hash"), rs.getString("secret_enc"), rs.getString("public_key"), rs.getString("api_key_id"),
        Json.read(rs.getString("limits"), AgentLimits.class), rs.getString("callback_url"), rs.getString("status"),
        ts(rs.getTimestamp("created_at")), ts(rs.getTimestamp("updated_at")), ts(rs.getTimestamp("last_seen_at")));

    private static final String SELECT = "select tenant_id, agent_id, owner, protocol, trust_tier, secret_hash, secret_enc, public_key, api_key_id, limits::text as limits, callback_url, status, created_at, updated_at, last_seen_at from agent_identity";

    private static Instant ts(Timestamp t) { return t == null ? null : t.toInstant(); }

    public void insert(Row r) {
        jdbc.sql("""
            insert into agent_identity(tenant_id, agent_id, owner, protocol, trust_tier, secret_hash, secret_enc, public_key, api_key_id, limits, callback_url, status)
            values (:t, :id, :owner, :protocol, :tier, :hash, :enc, :pk, :key, :limits, :cb, :status)
            """)
            .param("t", r.tenantId()).param("id", r.agentId()).param("owner", r.owner()).param("protocol", r.protocol()).param("tier", r.trustTier())
            .param("hash", r.secretHash()).param("enc", r.secretEnc()).param("pk", r.publicKey()).param("key", r.apiKeyId())
            .param("limits", Jsonb.of(r.limits())).param("cb", r.callbackUrl()).param("status", r.status()).update();
    }

    public void update(Row r) {
        jdbc.sql("""
            update agent_identity set owner = :owner, protocol = :protocol, trust_tier = :tier, secret_hash = :hash, secret_enc = :enc, public_key = :pk,
              api_key_id = :key, limits = :limits, callback_url = :cb, status = :status, updated_at = now()
            where tenant_id = :t and agent_id = :id
            """)
            .param("t", r.tenantId()).param("id", r.agentId()).param("owner", r.owner()).param("protocol", r.protocol()).param("tier", r.trustTier())
            .param("hash", r.secretHash()).param("enc", r.secretEnc()).param("pk", r.publicKey()).param("key", r.apiKeyId())
            .param("limits", Jsonb.of(r.limits())).param("cb", r.callbackUrl()).param("status", r.status()).update();
    }

    public Optional<Row> find(String agentId) {
        return jdbc.sql(SELECT + " where tenant_id = :t and agent_id = :id").param("t", TenantContext.require()).param("id", agentId).query(ROW).optional();
    }

    /** Tenants that know this agent id (used to resolve the tenant of a signed request without a tenant header). */
    public List<String> tenantsOf(String agentId) {
        return jdbc.sql("select tenant_id from agent_identity where agent_id = :id").param("id", agentId).query(String.class).list();
    }

    public List<Row> list() {
        return jdbc.sql(SELECT + " where tenant_id = :t order by created_at").param("t", TenantContext.require()).query(ROW).list();
    }

    public void touch(String agentId) {
        jdbc.sql("update agent_identity set last_seen_at = now() where tenant_id = :t and agent_id = :id").param("t", TenantContext.require()).param("id", agentId).update();
    }

    public void recordActivity(String agentId, String tool, String outcome, String orderId, Long latencyMs, Map<String, Object> details) {
        jdbc.sql("insert into agent_activity(tenant_id, agent_id, tool, outcome, order_id, latency_ms, details) values (:t, :id, :tool, :outcome, :order, :lat, :details)")
            .param("t", TenantContext.require()).param("id", agentId).param("tool", tool).param("outcome", outcome).param("order", orderId)
            .param("lat", latencyMs).param("details", Jsonb.of(details == null ? Map.of() : details)).update();
    }

    public List<AgentActivity> activity(String agentId, int limit) {
        return jdbc.sql("select id, agent_id, at, tool, outcome, order_id, latency_ms, details::text as details from agent_activity where tenant_id = :t and agent_id = :id order by at desc limit :n")
            .param("t", TenantContext.require()).param("id", agentId).param("n", Math.max(1, Math.min(500, limit)))
            .query((rs, i) -> new AgentActivity(rs.getLong("id"), rs.getString("agent_id"), ts(rs.getTimestamp("at")), rs.getString("tool"), rs.getString("outcome"),
                rs.getString("order_id"), rs.getObject("latency_ms") == null ? null : rs.getLong("latency_ms"), Json.readMap(rs.getString("details")))).list();
    }

    /** Orders the agent placed in the last 30 days (UI column {@code orders30d}). */
    public int orders30d(String agentId) {
        Integer n = jdbc.sql("select count(*) from orders where tenant_id = :t and provenance->>'agentId' = :id and placed_at > now() - interval '30 days'")
            .param("t", TenantContext.require()).param("id", agentId).query(Integer.class).single();
        return n == null ? 0 : n;
    }
}
