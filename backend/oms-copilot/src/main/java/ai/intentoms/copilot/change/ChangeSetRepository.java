package ai.intentoms.copilot.change;

import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.jdbc.Page;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** {@code copilot_changeset}: the ChangeSet document as jsonb plus status columns for listing. */
@Repository
public class ChangeSetRepository {
    private final JdbcClient jdbc;

    public ChangeSetRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void insert(ChangeSet cs) {
        jdbc.sql("insert into copilot_changeset(id, tenant_id, status, source, request, body, created_by) values (:id, :t, :s, :src, :r, :b, :by)")
            .param("id", cs.id()).param("t", TenantContext.require()).param("s", cs.status()).param("src", cs.source())
            .param("r", Jsonb.of(cs.request())).param("b", Jsonb.of(cs)).param("by", cs.createdBy()).update();
    }

    public void update(ChangeSet cs) {
        jdbc.sql("update copilot_changeset set status = :s, body = :b, updated_at = now() where tenant_id = :t and id = :id")
            .param("s", cs.status()).param("b", Jsonb.of(cs)).param("t", TenantContext.require()).param("id", cs.id()).update();
    }

    public Optional<ChangeSet> find(String id) {
        return jdbc.sql("select body::text from copilot_changeset where tenant_id = :t and id = :id").param("t", TenantContext.require()).param("id", id)
            .query(String.class).optional().map(s -> Json.read(s, ChangeSet.class));
    }

    public Page<ChangeSet> list(String status, int offset, int limit) {
        String t = TenantContext.require();
        int lim = limit <= 0 || limit > 200 ? 50 : limit;
        int off = Math.max(0, offset);
        String where = " where tenant_id = :t" + (status == null ? "" : " and status = :s");
        var count = jdbc.sql("select count(*) from copilot_changeset" + where).param("t", t);
        var rows = jdbc.sql("select body::text from copilot_changeset" + where + " order by created_at desc limit :l offset :o").param("t", t).param("l", lim).param("o", off);
        if (status != null) { count = count.param("s", status); rows = rows.param("s", status); }
        List<ChangeSet> items = rows.query(String.class).list().stream().map(s -> Json.read(s, ChangeSet.class)).toList();
        return Page.of(items, count.query(Long.class).single(), off, lim);
    }
}
