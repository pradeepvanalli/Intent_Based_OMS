package ai.intentoms.monitoring.exception;

import ai.intentoms.monitoring.playbook.PlaybookStep;
import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.jdbc.Page;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Persistence of {@code exception} rows (FR-MON-03/04). */
@Repository
public class ExceptionRepository {
    private static final TypeReference<List<PlaybookStep>> STEPS = new TypeReference<>() {};
    private static final String COLS = """
        id, order_id, line_id, node_id, type, severity, queue, status, source, code, message, details::text as details, classification_record_id, classification_confidence,
        playbook, playbook_steps::text as playbook_steps, playbook_run_at, decision_record_id, sla_due_at, assignee, task_id, resolution, resolution_note, resolved_at,
        occurrences, last_occurrence_at, source_event_id, created_at, updated_at""";
    private static final RowMapper<ExceptionRecord> MAPPER = (rs, i) -> map(rs);
    private final JdbcClient jdbc;

    public ExceptionRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    private static ExceptionRecord map(ResultSet rs) throws SQLException {
        return new ExceptionRecord(rs.getString("id"), rs.getString("order_id"), rs.getString("line_id"), rs.getString("node_id"), rs.getString("type"), rs.getString("severity"),
            rs.getString("queue"), rs.getString("status"), rs.getString("source"), rs.getString("code"), rs.getString("message"), Json.readMap(rs.getString("details")),
            rs.getString("classification_record_id"), rs.getObject("classification_confidence") == null ? null : rs.getDouble("classification_confidence"), rs.getString("playbook"),
            Json.read(rs.getString("playbook_steps"), STEPS), ts(rs.getTimestamp("playbook_run_at")), rs.getString("decision_record_id"), ts(rs.getTimestamp("sla_due_at")),
            rs.getString("assignee"), rs.getString("task_id"), rs.getString("resolution"), rs.getString("resolution_note"), ts(rs.getTimestamp("resolved_at")), rs.getInt("occurrences"),
            ts(rs.getTimestamp("last_occurrence_at")), rs.getString("source_event_id"), ts(rs.getTimestamp("created_at")), ts(rs.getTimestamp("updated_at")));
    }

    private static Instant ts(Timestamp t) { return t == null ? null : t.toInstant(); }
    private static Timestamp ts(Instant i) { return i == null ? null : Timestamp.from(i); }

    public ExceptionRecord insert(ExceptionRecord e) {
        jdbc.sql("""
            insert into exception(id, tenant_id, order_id, line_id, node_id, type, severity, queue, status, source, code, message, details, classification_record_id, classification_confidence,
              playbook, playbook_steps, playbook_run_at, decision_record_id, sla_due_at, assignee, task_id, occurrences, last_occurrence_at, source_event_id, created_at, updated_at)
            values (:id, :t, :order, :line, :node, :type, :sev, :queue, :status, :source, :code, :msg, :details, :crid, :conf, :pb, :steps, :runAt, :drid, :sla, :assignee, :task, :occ, :last, :sev_id, :created, :updated)
            """)
            .param("id", e.id()).param("t", TenantContext.require()).param("order", e.orderId()).param("line", e.lineId()).param("node", e.nodeId()).param("type", e.type())
            .param("sev", e.severity()).param("queue", e.queue()).param("status", e.status()).param("source", e.source()).param("code", e.code()).param("msg", e.message())
            .param("details", Jsonb.of(e.details())).param("crid", e.classificationRecordId()).param("conf", e.classificationConfidence()).param("pb", e.playbook())
            .param("steps", Jsonb.of(e.playbookSteps())).param("runAt", ts(e.playbookRunAt())).param("drid", e.decisionRecordId()).param("sla", ts(e.slaDueAt()))
            .param("assignee", e.assignee()).param("task", e.taskId()).param("occ", e.occurrences()).param("last", ts(e.lastOccurrenceAt())).param("sev_id", e.sourceEventId())
            .param("created", ts(e.createdAt())).param("updated", ts(e.updatedAt())).update();
        return e;
    }

    /** Rewrites every mutable column; {@code updated_at} is set to now. */
    public ExceptionRecord update(ExceptionRecord e) {
        jdbc.sql("""
            update exception set type = :type, severity = :sev, queue = :queue, status = :status, details = :details, classification_record_id = :crid, classification_confidence = :conf,
              playbook = :pb, playbook_steps = :steps, playbook_run_at = :runAt, decision_record_id = :drid, sla_due_at = :sla, assignee = :assignee, task_id = :task,
              resolution = :res, resolution_note = :note, resolved_at = :resolvedAt, occurrences = :occ, last_occurrence_at = :last, updated_at = now()
            where tenant_id = :t and id = :id
            """)
            .param("type", e.type()).param("sev", e.severity()).param("queue", e.queue()).param("status", e.status()).param("details", Jsonb.of(e.details()))
            .param("crid", e.classificationRecordId()).param("conf", e.classificationConfidence()).param("pb", e.playbook()).param("steps", Jsonb.of(e.playbookSteps()))
            .param("runAt", ts(e.playbookRunAt())).param("drid", e.decisionRecordId()).param("sla", ts(e.slaDueAt())).param("assignee", e.assignee()).param("task", e.taskId())
            .param("res", e.resolution()).param("note", e.resolutionNote()).param("resolvedAt", ts(e.resolvedAt())).param("occ", e.occurrences()).param("last", ts(e.lastOccurrenceAt()))
            .param("t", TenantContext.require()).param("id", e.id()).update();
        return get(e.id());
    }

    public Optional<ExceptionRecord> find(String id) {
        return jdbc.sql("select " + COLS + " from exception where tenant_id = :t and id = :id").param("t", TenantContext.require()).param("id", id).query(MAPPER).optional();
    }

    public ExceptionRecord get(String id) {
        return find(id).orElseThrow(() -> new ai.intentoms.platform.error.NotFoundException("Exception " + id + " not found"));
    }

    /** Active exception with the same (order, type, code) whose last occurrence is at or after {@code since} (de-duplication). */
    public Optional<ExceptionRecord> findActiveDuplicate(String orderId, String type, String code, Instant since) {
        String sql = "select " + COLS + " from exception where tenant_id = :t and type = :type and code = :code and status in ('OPEN', 'IN_PROGRESS') and last_occurrence_at >= :since"
            + (orderId == null ? " and order_id is null" : " and order_id = :order") + " order by created_at desc limit 1";
        var spec = jdbc.sql(sql).param("t", TenantContext.require()).param("type", type).param("code", code).param("since", Timestamp.from(since));
        if (orderId != null) spec = spec.param("order", orderId);
        return spec.query(MAPPER).optional();
    }

    /** Whether any exception (any status) of {@code type} exists for the order created at or after {@code since} (sweeper "once per window"). */
    public boolean existsSince(String orderId, String type, Instant since) {
        return jdbc.sql("select count(*) from exception where tenant_id = :t and order_id = :o and type = :type and created_at >= :since")
            .param("t", TenantContext.require()).param("o", orderId).param("type", type).param("since", Timestamp.from(since)).query(Long.class).single() > 0;
    }

    public Optional<ExceptionRecord> findBySourceEvent(String eventId) {
        return jdbc.sql("select " + COLS + " from exception where tenant_id = :t and source_event_id = :e order by created_at desc limit 1")
            .param("t", TenantContext.require()).param("e", eventId).query(MAPPER).optional();
    }

    public Page<ExceptionRecord> list(ExceptionQuery q) {
        StringBuilder where = new StringBuilder(" where tenant_id = :t");
        Map<String, Object> params = new HashMap<>();
        params.put("t", TenantContext.require());
        if (q.status() != null) {
            if (q.status().equalsIgnoreCase("active")) where.append(" and status in ('OPEN', 'IN_PROGRESS')");
            else { where.append(" and status = :status"); params.put("status", q.status().toUpperCase()); }
        }
        if (q.type() != null) { where.append(" and type = :type"); params.put("type", q.type()); }
        if (q.severity() != null) { where.append(" and severity = :sev"); params.put("sev", q.severity().toLowerCase()); }
        if (q.queue() != null) { where.append(" and queue = :queue"); params.put("queue", q.queue()); }
        if (q.orderId() != null) { where.append(" and order_id = :order"); params.put("order", q.orderId()); }
        if (q.assignee() != null) { where.append(" and assignee = :assignee"); params.put("assignee", q.assignee()); }
        long total = bind(jdbc.sql("select count(*) from exception" + where), params).query(Long.class).single();
        List<ExceptionRecord> items = bind(jdbc.sql("select " + COLS + " from exception" + where + " order by created_at desc limit :limit offset :offset"), params)
            .param("limit", q.limit()).param("offset", q.offset()).query(MAPPER).list();
        return Page.of(items, total, q.offset(), q.limit());
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, Map<String, Object> params) {
        for (var e : params.entrySet()) spec = spec.param(e.getKey(), e.getValue());
        return spec;
    }
}
