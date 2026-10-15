package ai.intentoms.orchestration.integration;

import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Exactly-once processing of inbound events keyed by {@code eventId} over the platform {@code idempotency_key} table
 * (NFR-06, FR-WF-05). The key is claimed in its own transaction; a replay returns the stored response with
 * {@code replayed=true}; a failure releases the claim so the sender can retry.
 */
@Component
public class WebhookIdempotency {
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public WebhookIdempotency(JdbcClient jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Map<String, Object> once(String scope, String eventId, Supplier<Map<String, Object>> body) {
        String tenant = TenantContext.require();
        Integer claimed = tx.execute(s -> jdbc.sql("insert into idempotency_key(tenant_id, scope, key, request_hash) values (:t, :s, :k, :h) on conflict (tenant_id, scope, key) do nothing")
            .param("t", tenant).param("s", scope).param("k", eventId).param("h", eventId).update());
        if (claimed == null || claimed == 0) {
            String stored = jdbc.sql("select response::text from idempotency_key where tenant_id = :t and scope = :s and key = :k").param("t", tenant).param("s", scope).param("k", eventId).query(String.class).optional().orElse(null);
            Map<String, Object> out = stored == null ? new LinkedHashMap<>(Map.of("accepted", true, "inProgress", true)) : new LinkedHashMap<>(Json.readMap(stored));
            out.put("replayed", true);
            out.put("eventId", eventId);
            return out;
        }
        try {
            Map<String, Object> result = new LinkedHashMap<>(body.get());
            result.put("replayed", false);
            result.put("eventId", eventId);
            tx.executeWithoutResult(s -> jdbc.sql("update idempotency_key set response = :r, status_code = 200 where tenant_id = :t and scope = :s and key = :k")
                .param("r", Jsonb.of(result)).param("t", tenant).param("s", scope).param("k", eventId).update());
            return result;
        } catch (RuntimeException e) {
            tx.executeWithoutResult(s -> jdbc.sql("delete from idempotency_key where tenant_id = :t and scope = :s and key = :k").param("t", tenant).param("s", scope).param("k", eventId).update());
            throw e;
        }
    }
}
