package ai.intentoms.orchestration.autonomy;

import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.jdbc.Page;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Persistence of {@code autonomous_action} (Agentic Control Tower audit trail). */
@Repository
public class AutonomousActionRepository {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final String COLS = "id, tenant_id, order_id, anomaly_type, decision_record_id, diagnosis_value, confidence, action_taken, "
        + "guardrail_snapshot::text as guardrail_snapshot, outcome, undoable, detail, details::text as details, executed_at, undone_at, undone_by, undo_reason";
    private static final RowMapper<AutonomousAction> MAPPER = (rs, i) -> new AutonomousAction(
        rs.getString("id"), rs.getString("tenant_id"), rs.getString("order_id"), rs.getString("anomaly_type"), rs.getString("decision_record_id"),
        rs.getString("diagnosis_value"), rs.getDouble("confidence"), rs.getString("action_taken"), Json.read(rs.getString("guardrail_snapshot"), MAP),
        rs.getString("outcome"), rs.getBoolean("undoable"), rs.getString("detail"), Json.read(rs.getString("details"), MAP), rs.getTimestamp("executed_at").toInstant(),
        rs.getTimestamp("undone_at") == null ? null : rs.getTimestamp("undone_at").toInstant(), rs.getString("undone_by"), rs.getString("undo_reason"));

    private final JdbcClient jdbc;

    public AutonomousActionRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public AutonomousAction insert(AutonomousAction a) {
        jdbc.sql("""
            insert into autonomous_action(id, tenant_id, order_id, anomaly_type, decision_record_id, diagnosis_value, confidence, action_taken,
              guardrail_snapshot, outcome, undoable, detail, details, executed_at)
            values (:id, :t, :o, :at, :rec, :val, :conf, :action, :snap, :outcome, :undoable, :detail, :details, :when)
            """).param("id", a.id()).param("t", TenantContext.require()).param("o", a.orderId()).param("at", a.anomalyType())
            .param("rec", a.decisionRecordId()).param("val", a.diagnosisValue()).param("conf", a.confidence()).param("action", a.actionTaken())
            .param("snap", Jsonb.of(a.guardrailSnapshot())).param("outcome", a.outcome()).param("undoable", a.undoable()).param("detail", a.detail())
            .param("details", Jsonb.of(a.details())).param("when", Timestamp.from(a.executedAt())).update();
        return a;
    }

    public AutonomousAction get(String id) {
        return find(id).orElseThrow(() -> new ai.intentoms.platform.error.NotFoundException("Autonomous action " + id + " not found", Map.of("id", id)));
    }

    public Optional<AutonomousAction> find(String id) {
        return jdbc.sql("select " + COLS + " from autonomous_action where tenant_id = :t and id = :id").param("t", TenantContext.require()).param("id", id).query(MAPPER).optional();
    }

    public Page<AutonomousAction> list(Instant from, Instant to, String actionType, String outcome, int offset, int limit) {
        var spec = jdbc.sql("select " + COLS + " from autonomous_action where tenant_id = :t "
                + "and (:from::timestamptz is null or executed_at >= :from::timestamptz) and (:to::timestamptz is null or executed_at < :to::timestamptz) "
                + "and (:actionType::text is null or action_taken = :actionType::text) and (:outcome::text is null or outcome = :outcome::text) "
                + "order by executed_at desc limit :limit offset :offset")
            .param("t", TenantContext.require()).param("from", from == null ? null : Timestamp.from(from)).param("to", to == null ? null : Timestamp.from(to))
            .param("actionType", actionType).param("outcome", outcome).param("limit", limit).param("offset", offset);
        List<AutonomousAction> items = spec.query(MAPPER).list();
        long total = jdbc.sql("select count(*) from autonomous_action where tenant_id = :t "
                + "and (:from::timestamptz is null or executed_at >= :from::timestamptz) and (:to::timestamptz is null or executed_at < :to::timestamptz) "
                + "and (:actionType::text is null or action_taken = :actionType::text) and (:outcome::text is null or outcome = :outcome::text)")
            .param("t", TenantContext.require()).param("from", from == null ? null : Timestamp.from(from)).param("to", to == null ? null : Timestamp.from(to))
            .param("actionType", actionType).param("outcome", outcome).query(Long.class).single();
        return Page.of(items, total, offset, limit);
    }

    /** Autonomous actions executed today (UTC), used by the daily cap guardrail and the stats endpoint. */
    public int countExecutedToday() {
        return jdbc.sql("select count(*) from autonomous_action where tenant_id = :t and outcome = 'executed' and executed_at >= date_trunc('day', now())")
            .param("t", TenantContext.require()).query(Integer.class).single();
    }

    /** Estimated USD impact resolved autonomously today (sum of each executed action's snapshot {@code estimatedImpactUsd}). */
    public java.math.BigDecimal sumEstimatedImpactUsdToday() {
        java.math.BigDecimal v = jdbc.sql("select coalesce(sum((guardrail_snapshot->>'estimatedImpactUsd')::numeric), 0) from autonomous_action "
                + "where tenant_id = :t and outcome = 'executed' and executed_at >= date_trunc('day', now())")
            .param("t", TenantContext.require()).query(java.math.BigDecimal.class).single();
        return v == null ? java.math.BigDecimal.ZERO : v;
    }

    public void markUndone(String id, Instant at, String by, String reason) {
        jdbc.sql("update autonomous_action set undone_at = :at, undone_by = :by, undo_reason = :reason where tenant_id = :t and id = :id")
            .param("at", Timestamp.from(at)).param("by", by).param("reason", reason).param("t", TenantContext.require()).param("id", id).update();
    }
}
