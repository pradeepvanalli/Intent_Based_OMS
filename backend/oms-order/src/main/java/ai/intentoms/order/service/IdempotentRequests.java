package ai.intentoms.order.service;

import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.function.Supplier;

/**
 * {@code Idempotency-Key} support for mutating order endpoints over the platform {@code idempotency_key} table (FR-CAP-04,
 * ARCHITECTURE §2 "Mutations accept optional header Idempotency-Key"). The key is claimed in its own short transaction
 * before the body runs so two concurrent retries cannot both execute; a replay with the same request body returns the
 * stored response, a replay with a different body is rejected (409 {@code idempotency_key_reused}), and a replay that
 * arrives while the first attempt is still running gets 409 {@code request_in_progress}. The body runs in the caller's
 * transaction; on failure the claim is released so the client can retry.
 */
@Component
public class IdempotentRequests {
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public IdempotentRequests(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(tx.getTransactionManager());
        this.tx.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    /** Runs {@code body} once per (scope, key); returns the stored response of a matching replay. */
    public <T> T execute(String scope, String key, Object request, TypeReference<T> responseType, Supplier<T> body) {
        return executeDetailed(scope, key, request, responseType, body).value();
    }

    public record Outcome<T>(T value, boolean replayed) {}

    public <T> Outcome<T> executeDetailed(String scope, String key, Object request, TypeReference<T> responseType, Supplier<T> body) {
        if (key == null || key.isBlank()) return new Outcome<>(body.get(), false);
        String tenant = TenantContext.require();
        String hash = Json.sha256(request);
        Integer claimed = tx.execute(s -> jdbc.sql("insert into idempotency_key(tenant_id, scope, key, request_hash) values (:t, :s, :k, :h) on conflict (tenant_id, scope, key) do nothing")
            .param("t", tenant).param("s", scope).param("k", key).param("h", hash).update());
        if (claimed == null || claimed == 0) {
            var existing = jdbc.sql("select request_hash, response::text as response from idempotency_key where tenant_id = :t and scope = :s and key = :k")
                .param("t", tenant).param("s", scope).param("k", key).query().singleRow();
            if (!hash.equals(existing.get("request_hash"))) throw new ConflictException("idempotency_key_reused", Map.of("scope", scope, "key", key));
            String response = (String) existing.get("response");
            if (response == null) throw new ConflictException("request_in_progress", Map.of("scope", scope, "key", key));
            return new Outcome<>(Json.read(response, responseType), true);
        }
        try {
            T result = body.get();
            tx.executeWithoutResult(s -> jdbc.sql("update idempotency_key set response = :r, status_code = 200 where tenant_id = :t and scope = :s and key = :k")
                .param("r", Jsonb.of(result)).param("t", tenant).param("s", scope).param("k", key).update());
            return new Outcome<>(result, false);
        } catch (RuntimeException e) {
            tx.executeWithoutResult(s -> jdbc.sql("delete from idempotency_key where tenant_id = :t and scope = :s and key = :k").param("t", tenant).param("s", scope).param("k", key).update());
            throw e;
        }
    }
}
