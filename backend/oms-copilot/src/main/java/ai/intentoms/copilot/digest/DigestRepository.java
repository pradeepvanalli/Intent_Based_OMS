package ai.intentoms.copilot.digest;

import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Stored digests ({@code copilot_digest}). */
@Repository
public class DigestRepository {
    private final JdbcClient jdbc;

    public DigestRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void insert(Digest d, int days, String by) {
        jdbc.sql("insert into copilot_digest(id, tenant_id, days, body, generated_by) values (:id, :t, :d, :b, :by)")
            .param("id", d.id()).param("t", TenantContext.require()).param("d", days).param("b", Jsonb.of(d)).param("by", by).update();
    }

    public Optional<Digest> latest() {
        return jdbc.sql("select body::text from copilot_digest where tenant_id = :t order by created_at desc limit 1").param("t", TenantContext.require()).query(String.class).optional().map(s -> Json.read(s, Digest.class));
    }

    public List<Digest> list(int limit) {
        return jdbc.sql("select body::text from copilot_digest where tenant_id = :t order by created_at desc limit :l").param("t", TenantContext.require()).param("l", Math.max(1, Math.min(limit, 50)))
            .query(String.class).list().stream().map(s -> Json.read(s, Digest.class)).toList();
    }
}
