package ai.intentoms.decision.definition;

import ai.intentoms.decision.model.DecisionDefinition;
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

/**
 * Persistence for decision_definition rows. Definitions are always re-parsed from their YAML so the stored row is the
 * single source of truth; the {@code parsed} jsonb column exists for SQL-side inspection only.
 */
@Repository
public class DecisionDefinitionRepository {
    private final JdbcClient jdbc;
    private final DecisionDefinitionParser parser;

    /** Governance metadata stored next to the definition. */
    public record Row(DecisionDefinition definition, String createdBy, Instant updatedAt, String submittedBy, Instant submittedAt, Instant publishedAt, Instant retiredAt,
                      List<Map<String, Object>> approvals, Map<String, Object> rollout, Map<String, Object> evaluationReport, Map<String, Object> lintReport, Instant retroReviewDueAt) {}

    public DecisionDefinitionRepository(JdbcClient jdbc, DecisionDefinitionParser parser) {
        this.jdbc = jdbc;
        this.parser = parser;
    }

    private Row mapRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        DecisionDefinition base = parser.parse(rs.getString("yaml"));
        DecisionDefinition def = base.withVersion(rs.getInt("version")).withStatus(rs.getString("status"), rs.getInt("canary_percent"), rs.getString("change_reason"), rs.getString("ticket"),
            rs.getTimestamp("created_at").toInstant());
        return new Row(def, rs.getString("created_by"), ts(rs.getTimestamp("updated_at")), rs.getString("submitted_by"), ts(rs.getTimestamp("submitted_at")), ts(rs.getTimestamp("published_at")),
            ts(rs.getTimestamp("retired_at")), Json.read(rs.getString("approvals"), new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {}),
            Json.readMap(rs.getString("rollout")), rs.getString("evaluation_report") == null ? null : Json.readMap(rs.getString("evaluation_report")),
            rs.getString("lint_report") == null ? null : Json.readMap(rs.getString("lint_report")), ts(rs.getTimestamp("retro_review_due_at")));
    }

    private final RowMapper<Row> mapper = this::mapRow;

    private static Instant ts(Timestamp t) { return t == null ? null : t.toInstant(); }

    private static final String COLS = "tenant_id, name, version, status, tier, yaml, canary_percent, change_reason, ticket, created_by, created_at, updated_at, submitted_by, submitted_at, " +
        "published_at, retired_at, approvals::text as approvals, rollout::text as rollout, evaluation_report::text as evaluation_report, lint_report::text as lint_report, retro_review_due_at";

    public void insert(DecisionDefinition def, String createdBy) {
        jdbc.sql("insert into decision_definition(tenant_id, name, version, status, tier, yaml, parsed, canary_percent, change_reason, ticket, created_by) " +
                "values (:t, :n, :v, :s, :tier, :y, :p, :c, :cr, :tk, :by)")
            .param("t", TenantContext.require()).param("n", def.name()).param("v", def.version()).param("s", def.status()).param("tier", def.tier().name())
            .param("y", def.yaml()).param("p", Jsonb.of(def)).param("c", def.canaryPercent()).param("cr", def.changeReason()).param("tk", def.ticket()).param("by", createdBy).update();
    }

    /** Replace the YAML of an existing (draft) version and clear stale reports. */
    public void updateYaml(String name, int version, DecisionDefinition def) {
        jdbc.sql("update decision_definition set yaml = :y, parsed = :p, tier = :tier, updated_at = now(), evaluation_report = null, lint_report = null " +
                "where tenant_id = :t and name = :n and version = :v")
            .param("y", def.yaml()).param("p", Jsonb.of(def)).param("tier", def.tier().name()).param("t", TenantContext.require()).param("n", name).param("v", version).update();
    }

    public Optional<Row> find(String name, int version) {
        return jdbc.sql("select " + COLS + " from decision_definition where tenant_id = :t and name = :n and version = :v")
            .param("t", TenantContext.require()).param("n", name).param("v", version).query(mapper).optional();
    }

    public List<Row> versions(String name) {
        return jdbc.sql("select " + COLS + " from decision_definition where tenant_id = :t and name = :n order by version desc")
            .param("t", TenantContext.require()).param("n", name).query(mapper).list();
    }

    /** Every version with the given status for a decision (live, canary and shadow may coexist). */
    public List<Row> byStatus(String name, String... statuses) {
        return jdbc.sql("select " + COLS + " from decision_definition where tenant_id = :t and name = :n and status in (:s) order by version desc")
            .param("t", TenantContext.require()).param("n", name).param("s", List.of(statuses)).query(mapper).list();
    }

    /** All non-retired, non-draft versions of every decision (used by the runtime's resolver cache warm-up and listings). */
    public List<Row> activeAll() {
        return jdbc.sql("select " + COLS + " from decision_definition where tenant_id = :t and status in ('live','canary','shadow') order by name, version desc")
            .param("t", TenantContext.require()).query(mapper).list();
    }

    public List<String> names() {
        return jdbc.sql("select distinct name from decision_definition where tenant_id = :t order by name").param("t", TenantContext.require()).query(String.class).list();
    }

    public int nextVersion(String name) {
        return jdbc.sql("select coalesce(max(version), 0) + 1 from decision_definition where tenant_id = :t and name = :n")
            .param("t", TenantContext.require()).param("n", name).query(Integer.class).single();
    }

    public void setStatus(String name, int version, String status, Integer canaryPercent) {
        StringBuilder sql = new StringBuilder("update decision_definition set status = :s, updated_at = now()");
        if (canaryPercent != null) sql.append(", canary_percent = :c");
        if ("retired".equals(status)) sql.append(", retired_at = now()");
        if (status.equals("live") || status.equals("canary") || status.equals("shadow")) sql.append(", published_at = coalesce(published_at, now())");
        sql.append(" where tenant_id = :t and name = :n and version = :v");
        var spec = jdbc.sql(sql.toString()).param("s", status).param("t", TenantContext.require()).param("n", name).param("v", version);
        if (canaryPercent != null) spec = spec.param("c", canaryPercent);
        spec.update();
    }

    public void setSubmitted(String name, int version, String by, String reason, String ticket) {
        jdbc.sql("update decision_definition set status = 'pending_approval', submitted_by = :by, submitted_at = now(), change_reason = :r, ticket = :tk, approvals = '[]'::jsonb, updated_at = now() " +
                "where tenant_id = :t and name = :n and version = :v")
            .param("by", by).param("r", reason).param("tk", ticket).param("t", TenantContext.require()).param("n", name).param("v", version).update();
    }

    public void setApprovals(String name, int version, List<Map<String, Object>> approvals, String status) {
        jdbc.sql("update decision_definition set approvals = :a, status = :s, updated_at = now() where tenant_id = :t and name = :n and version = :v")
            .param("a", Jsonb.of(approvals)).param("s", status).param("t", TenantContext.require()).param("n", name).param("v", version).update();
    }

    public void setRollout(String name, int version, Map<String, Object> rollout, Instant retroReviewDueAt) {
        jdbc.sql("update decision_definition set rollout = :r, retro_review_due_at = :rr, updated_at = now() where tenant_id = :t and name = :n and version = :v")
            .param("r", Jsonb.of(rollout)).param("rr", retroReviewDueAt == null ? null : Timestamp.from(retroReviewDueAt))
            .param("t", TenantContext.require()).param("n", name).param("v", version).update();
    }

    public void setEvaluationReport(String name, int version, Object report) {
        jdbc.sql("update decision_definition set evaluation_report = :r, updated_at = now() where tenant_id = :t and name = :n and version = :v")
            .param("r", Jsonb.of(report)).param("t", TenantContext.require()).param("n", name).param("v", version).update();
    }

    public void setLintReport(String name, int version, Object report) {
        jdbc.sql("update decision_definition set lint_report = :r, updated_at = now() where tenant_id = :t and name = :n and version = :v")
            .param("r", Jsonb.of(report)).param("t", TenantContext.require()).param("n", name).param("v", version).update();
    }

    /** Most recently retired version (for one-click rollback, FR-PRM-04). */
    public Optional<Row> lastRetired(String name) {
        return jdbc.sql("select " + COLS + " from decision_definition where tenant_id = :t and name = :n and status = 'retired' and published_at is not null order by retired_at desc nulls last, version desc limit 1")
            .param("t", TenantContext.require()).param("n", name).query(mapper).optional();
    }
}
