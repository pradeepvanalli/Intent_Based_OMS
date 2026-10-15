package ai.intentoms.finance.api;

import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Replay protection for mutating finance endpoints using the platform {@code idempotency_key} table: the same
 * {@code Idempotency-Key} with the same body returns the stored response; the same key with a different body is a
 * 409 conflict. Keys are scoped per endpoint and tenant.
 */
@Component
public class IdempotentRequests {
    private final JdbcClient jdbc;

    public IdempotentRequests(JdbcClient jdbc) { this.jdbc = jdbc; }

    public <T> T execute(String scope, String key, Object request, Class<T> responseType, Supplier<T> body) {
        if (key == null || key.isBlank()) return body.get();
        String tenant = TenantContext.require();
        String hash = Json.sha256(request);
        Optional<String[]> existing = jdbc.sql("select request_hash, response::text as response from idempotency_key where tenant_id = :t and scope = :s and key = :k")
            .param("t", tenant).param("s", scope).param("k", key)
            .query((rs, i) -> new String[]{rs.getString("request_hash"), rs.getString("response")}).optional();
        if (existing.isPresent()) {
            if (!hash.equals(existing.get()[0])) throw new ConflictException("Idempotency-Key '" + key + "' was already used with a different request body");
            return Json.read(existing.get()[1], responseType);
        }
        T result = body.get();
        try {
            jdbc.sql("insert into idempotency_key(tenant_id, scope, key, request_hash, response, status_code) values (:t, :s, :k, :h, :r, 200)")
                .param("t", tenant).param("s", scope).param("k", key).param("h", hash).param("r", Jsonb.of(result)).update();
        } catch (DuplicateKeyException race) {
            // A concurrent identical request won the insert; our result is equivalent.
        }
        return result;
    }
}
