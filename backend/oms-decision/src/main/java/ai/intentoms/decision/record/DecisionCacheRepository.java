package ai.intentoms.decision.record;

import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/** Result cache keyed by definition version and input hash with TTL (FR-DEC-11). */
@Repository
public class DecisionCacheRepository {
    private final JdbcClient jdbc;

    public DecisionCacheRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Optional<DecisionResult> get(String definitionKey, String inputHash, Instant now) {
        return jdbc.sql("select result::text from decision_cache where tenant_id = :t and definition_key = :k and input_hash = :h and expires_at > :now")
            .param("t", TenantContext.require()).param("k", definitionKey).param("h", inputHash).param("now", Timestamp.from(now)).query(String.class).optional()
            .map(s -> Json.read(s, DecisionResult.class));
    }

    public void put(String definitionKey, String inputHash, DecisionResult result, Instant expiresAt) {
        jdbc.sql("insert into decision_cache(tenant_id, definition_key, input_hash, result, expires_at) values (:t, :k, :h, :r, :e) " +
                "on conflict (tenant_id, definition_key, input_hash) do update set result = excluded.result, expires_at = excluded.expires_at")
            .param("t", TenantContext.require()).param("k", definitionKey).param("h", inputHash).param("r", Jsonb.of(result)).param("e", Timestamp.from(expiresAt)).update();
    }

    public int purgeExpired(Instant now) {
        return jdbc.sql("delete from decision_cache where expires_at <= :now").param("now", Timestamp.from(now)).update();
    }
}
