package ai.intentoms.decision.studio;

import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Persistence for {@code prompt_version} (DF-06). */
@Repository
public class PromptVersionRepository {
    private final JdbcClient jdbc;
    public PromptVersionRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    private static final String COLS = "decision, question, version, instructions, status, change_reason, ticket, lint_report::text as lint_report, "
        + "evaluation_report::text as evaluation_report, studio_version, created_by, created_at, updated_at, published_at";

    public int nextVersion(String decision, String question) {
        return jdbc.sql("select coalesce(max(version), 0) + 1 from prompt_version where tenant_id = :t and decision = :d and question = :q")
            .param("t", TenantContext.require()).param("d", decision).param("q", question).query(Integer.class).single();
    }

    public void insert(PromptVersion p) {
        jdbc.sql("""
            insert into prompt_version(tenant_id, decision, question, version, instructions, status, change_reason, ticket, created_by, created_at, updated_at)
            values (:t, :d, :q, :v, :text, :s, :reason, :ticket, :by, :at, :at)
            """).param("t", TenantContext.require()).param("d", p.decision()).param("q", p.question()).param("v", p.version()).param("text", p.text())
            .param("s", p.status()).param("reason", p.changeReason()).param("ticket", p.ticket()).param("by", p.createdBy()).param("at", Timestamp.from(p.createdAt())).update();
    }

    public PromptVersion get(String decision, String question, int version) {
        return many("select " + COLS + " from prompt_version where tenant_id = :t and decision = :d and question = :q and version = :v",
            Map.of("d", decision, "q", question, "v", version)).stream().findFirst()
            .orElseThrow(() -> new NotFoundException("Prompt " + decision + "/" + question + " version " + version + " not found"));
    }

    public List<PromptVersion> forDecision(String decision) {
        return many("select " + COLS + " from prompt_version where tenant_id = :t and decision = :d order by question, version desc", Map.of("d", decision));
    }

    public void setLint(String decision, String question, int version, Map<String, Object> report) {
        update("lint_report = :r", decision, question, version, Map.of("r", Jsonb.of(report)));
    }

    public void setEvaluation(String decision, String question, int version, int studioVersion, Map<String, Object> report, String status) {
        update("evaluation_report = :r, studio_version = :sv, status = :s", decision, question, version, Map.of("r", Jsonb.of(report), "sv", studioVersion, "s", status));
    }

    public void setStatus(String decision, String question, int version, String status) {
        update("status = :s", decision, question, version, Map.of("s", status));
    }

    public void markPublished(String decision, String question, int version, Instant at) {
        jdbc.sql("update prompt_version set status = 'retired', updated_at = now() where tenant_id = :t and decision = :d and question = :q and status = 'live' and version <> :v")
            .param("t", TenantContext.require()).param("d", decision).param("q", question).param("v", version).update();
        update("status = 'live', published_at = :at", decision, question, version, Map.of("at", Timestamp.from(at)));
    }

    private void update(String set, String decision, String question, int version, Map<String, Object> params) {
        var spec = jdbc.sql("update prompt_version set " + set + ", updated_at = now() where tenant_id = :t and decision = :d and question = :q and version = :v")
            .param("t", TenantContext.require()).param("d", decision).param("q", question).param("v", version);
        for (var p : params.entrySet()) spec = spec.param(p.getKey(), p.getValue());
        spec.update();
    }

    private List<PromptVersion> many(String sql, Map<String, Object> params) {
        var spec = jdbc.sql(sql).param("t", TenantContext.require());
        for (var p : params.entrySet()) spec = spec.param(p.getKey(), p.getValue());
        return spec.query().listOfRows().stream().map(PromptVersionRepository::hydrate).toList();
    }

    private static PromptVersion hydrate(Map<String, Object> r) {
        return new PromptVersion((String) r.get("decision"), (String) r.get("question"), (Integer) r.get("version"), (String) r.get("instructions"),
            (String) r.get("status"), (String) r.get("change_reason"), (String) r.get("ticket"), json(r.get("lint_report")), json(r.get("evaluation_report")),
            (Integer) r.get("studio_version"), (String) r.get("created_by"), ts(r.get("created_at")), ts(r.get("updated_at")), ts(r.get("published_at")));
    }

    private static Map<String, Object> json(Object o) { return o == null ? null : Json.readMap((String) o); }
    private static Instant ts(Object o) { return o == null ? null : ((Timestamp) o).toInstant(); }
}
