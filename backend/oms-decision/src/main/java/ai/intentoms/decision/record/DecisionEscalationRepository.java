package ai.intentoms.decision.record;

import ai.intentoms.decision.model.DecisionEscalation;
import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** decision_escalation rows written by the default {@code RecordingEscalationHandler}. */
@Repository
public class DecisionEscalationRepository {
    private final JdbcClient jdbc;

    public DecisionEscalationRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<DecisionEscalation> MAPPER = (rs, i) -> new DecisionEscalation(rs.getString("id"), rs.getString("record_id"), rs.getString("decision"), rs.getString("status"),
        rs.getString("task_id"), rs.getString("resolved_value") == null ? null : Json.read(rs.getString("resolved_value"), Object.class), rs.getString("resolved_by"),
        rs.getTimestamp("resolved_at") == null ? null : rs.getTimestamp("resolved_at").toInstant(), rs.getTimestamp("created_at").toInstant());

    private static final String COLS = "id, record_id, decision, status, task_id, resolved_value::text as resolved_value, resolved_by, resolved_at, created_at";

    public void insert(String id, String recordId, String decision, String taskId) {
        jdbc.sql("insert into decision_escalation(id, tenant_id, record_id, decision, task_id) values (:id, :t, :r, :d, :task)")
            .param("id", id).param("t", TenantContext.require()).param("r", recordId).param("d", decision).param("task", taskId).update();
    }

    public Optional<DecisionEscalation> find(String id) {
        return jdbc.sql("select " + COLS + " from decision_escalation where tenant_id = :t and id = :id").param("t", TenantContext.require()).param("id", id).query(MAPPER).optional();
    }

    public List<DecisionEscalation> open(String decision) {
        return jdbc.sql("select " + COLS + " from decision_escalation where tenant_id = :t and status = 'OPEN' and (:d is null or decision = :d) order by created_at")
            .param("t", TenantContext.require()).param("d", decision).query(MAPPER).list();
    }

    /** Insert an OPEN escalation that records its subject. With {@code dedupe} it becomes the one OPEN anchor for this
     * (decision, subject); returns false, without aborting the transaction, when a concurrent evaluation already
     * holds the anchor (partial unique index {@code decision_escalation_open_subject_uq}). */
    public boolean insert(String id, String recordId, String decision, String taskId, String subjectType, String subjectId, boolean dedupe) {
        String conflict = dedupe ? " on conflict (tenant_id, decision, subject_type, subject_id) where status = 'OPEN' and dedupe and subject_id is not null do nothing" : "";
        return jdbc.sql("""
            insert into decision_escalation(id, tenant_id, record_id, decision, task_id, subject_type, subject_id, dedupe)
            values (:id, :t, :r, :d, :task, :st, :sid, :dedupe)""" + conflict)
            .param("id", id).param("t", TenantContext.require()).param("r", recordId).param("d", decision).param("task", taskId)
            .param("st", subjectType).param("sid", subjectId).param("dedupe", dedupe).update() == 1;
    }

    /** A re-escalation of a question already waiting on a person: stored LINKED to that task, resolved with it. */
    public void insertLinked(String id, String recordId, String decision, String taskId, String subjectType, String subjectId) {
        jdbc.sql("""
            insert into decision_escalation(id, tenant_id, record_id, decision, task_id, subject_type, subject_id, dedupe, status)
            values (:id, :t, :r, :d, :task, :st, :sid, true, 'LINKED')
            """)
            .param("id", id).param("t", TenantContext.require()).param("r", recordId).param("d", decision).param("task", taskId)
            .param("st", subjectType).param("sid", subjectId).update();
    }

    /** The OPEN de-duplicated anchor escalation of this decision about this subject, if any. */
    public Optional<DecisionEscalation> openAnchorForSubject(String decision, String subjectType, String subjectId) {
        if (subjectType == null || subjectId == null) return Optional.empty();
        return jdbc.sql("select " + COLS + " from decision_escalation where tenant_id = :t and status = 'OPEN' and dedupe and decision = :d and subject_type = :st and subject_id = :sid and task_id is not null")
            .param("t", TenantContext.require()).param("d", decision).param("st", subjectType).param("sid", subjectId).query(MAPPER).optional();
    }

    /** Every unresolved (OPEN or LINKED) escalation attached to one human task. */
    public List<DecisionEscalation> unresolvedForTask(String taskId) {
        return jdbc.sql("select " + COLS + " from decision_escalation where tenant_id = :t and status in ('OPEN','LINKED') and task_id = :task order by created_at")
            .param("t", TenantContext.require()).param("task", taskId).query(MAPPER).list();
    }

    /** Every escalation ever attached to one human task, whatever its status. */
    public List<DecisionEscalation> forTask(String taskId) {
        return jdbc.sql("select " + COLS + " from decision_escalation where tenant_id = :t and task_id = :task order by created_at")
            .param("t", TenantContext.require()).param("task", taskId).query(MAPPER).list();
    }

    public void resolve(String id, Object value, String by, Instant at) {
        jdbc.sql("update decision_escalation set status = 'RESOLVED', resolved_value = :v, resolved_by = :by, resolved_at = :at where tenant_id = :t and id = :id")
            .param("v", Jsonb.of(Json.write(value))).param("by", by).param("at", Timestamp.from(at)).param("t", TenantContext.require()).param("id", id).update();
    }
}
