package ai.intentoms.copilot.change;

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

/** {@code copilot_draft_artifact} persistence. */
@Repository
public class DraftArtifactRepository {
    private final JdbcClient jdbc;
    private final RowMapper<DraftArtifact> mapper = (rs, i) -> new DraftArtifact(rs.getString("id"), rs.getString("changeset_id"), rs.getString("change_id"),
        ChangeKind.valueOf(rs.getString("kind")), rs.getString("target"), rs.getString("content"), rs.getString("summary"), rs.getString("status"), rs.getString("created_by"),
        ts(rs.getTimestamp("created_at")), rs.getString("applied_by"), ts(rs.getTimestamp("applied_at")), rs.getString("apply_reason"),
        rs.getString("result") == null ? Map.of() : Json.readMap(rs.getString("result")));

    public DraftArtifactRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    private static final String COLS = "id, changeset_id, change_id, kind, target, content, summary, status, created_by, created_at, applied_by, applied_at, apply_reason, result::text as result";

    public DraftArtifact insert(DraftArtifact a) {
        jdbc.sql("insert into copilot_draft_artifact(id, tenant_id, changeset_id, change_id, kind, target, content, summary, status, created_by) values (:id, :t, :cs, :c, :k, :tg, :ct, :s, 'draft', :by)")
            .param("id", a.id()).param("t", TenantContext.require()).param("cs", a.changesetId()).param("c", a.changeId()).param("k", a.kind().name()).param("tg", a.target())
            .param("ct", a.content()).param("s", a.summary()).param("by", a.createdBy()).update();
        return get(a.id());
    }

    public Optional<DraftArtifact> find(String id) {
        return jdbc.sql("select " + COLS + " from copilot_draft_artifact where tenant_id = :t and id = :id").param("t", TenantContext.require()).param("id", id).query(mapper).optional();
    }

    public DraftArtifact get(String id) { return find(id).orElseThrow(() -> new ai.intentoms.platform.error.NotFoundException("Draft artefact " + id + " not found")); }

    public List<DraftArtifact> list(String status, String changesetId) {
        String where = " where tenant_id = :t" + (status == null ? "" : " and status = :s") + (changesetId == null ? "" : " and changeset_id = :cs");
        var q = jdbc.sql("select " + COLS + " from copilot_draft_artifact" + where + " order by created_at desc").param("t", TenantContext.require());
        if (status != null) q = q.param("s", status);
        if (changesetId != null) q = q.param("cs", changesetId);
        return q.query(mapper).list();
    }

    public void markApplied(String id, String by, String reason, Map<String, Object> result, Instant at) {
        jdbc.sql("update copilot_draft_artifact set status = 'applied', applied_by = :by, applied_at = :at, apply_reason = :r, result = :res where tenant_id = :t and id = :id")
            .param("by", by).param("at", Timestamp.from(at)).param("r", reason).param("res", Jsonb.of(result)).param("t", TenantContext.require()).param("id", id).update();
    }

    public void markRejected(String id, String by, String reason, Instant at) {
        jdbc.sql("update copilot_draft_artifact set status = 'rejected', applied_by = :by, applied_at = :at, apply_reason = :r where tenant_id = :t and id = :id")
            .param("by", by).param("at", Timestamp.from(at)).param("r", reason).param("t", TenantContext.require()).param("id", id).update();
    }

    private static Instant ts(Timestamp t) { return t == null ? null : t.toInstant(); }
}
