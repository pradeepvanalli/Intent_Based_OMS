package ai.intentoms.inventory.api;

import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Supplier;

/**
 * {@code Idempotency-Key} support for mutating inventory endpoints over the platform {@code idempotency_key} table.
 * A key is claimed before the body runs so two concurrent retries cannot both execute; a replay with the same request
 * returns the stored response, a replay with a different request body is rejected (409 {@code idempotency_key_reused}).
 */
@Component
public class IdempotentRequests {
    private final JdbcClient jdbc;

    public IdempotentRequests(JdbcClient jdbc) { this.jdbc = jdbc; }

    public <T> T execute(String scope, String key, Object request, TypeReference<T> responseType, Supplier<T> body) {
        if (key == null || key.isBlank()) return body.get();
        String tenant = TenantContext.require();
        String hash = Json.sha256(request);
        try {
            jdbc.sql("insert into idempotency_key(tenant_id, scope, key, request_hash) values (:t, :s, :k, :h)")
                .param("t", tenant).param("s", scope).param("k", key).param("h", hash).update();
        } catch (DuplicateKeyException dup) {
            var existing = jdbc.sql("select request_hash, response::text as response from idempotency_key where tenant_id = :t and scope = :s and key = :k")
                .param("t", tenant).param("s", scope).param("k", key).query().singleRow();
            if (!hash.equals(existing.get("request_hash")))
                throw new ConflictException("idempotency_key_reused", Map.of("scope", scope, "key", key));
            String response = (String) existing.get("response");
            if (response == null) throw new ConflictException("request_in_progress", Map.of("scope", scope, "key", key));
            return Json.read(response, responseType);
        }
        try {
            T result = body.get();
            jdbc.sql("update idempotency_key set response = :r, status_code = 200 where tenant_id = :t and scope = :s and key = :k")
                .param("r", Jsonb.of(result)).param("t", tenant).param("s", scope).param("k", key).update();
            return result;
        } catch (RuntimeException e) {
            jdbc.sql("delete from idempotency_key where tenant_id = :t and scope = :s and key = :k").param("t", tenant).param("s", scope).param("k", key).update();
            throw e;
        }
    }
}
