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
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Persistence of {@code autonomy_anomaly} (Agentic Control Tower live feed). */
@Repository
public class AnomalyRepository {
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
    private static final String COLS = "id, tenant_id, order_id, anomaly_type, decision_record_id, diagnosis_value, confidence, band, "
        + "reason_codes::text as reason_codes, outcome, action_id, escalation_task_id, escalation_queue, escalation_reason, detected_at";
    private static final RowMapper<Anomaly> MAPPER = (rs, i) -> new Anomaly(
        rs.getString("id"), rs.getString("tenant_id"), rs.getString("order_id"), rs.getString("anomaly_type"), rs.getString("decision_record_id"),
        rs.getString("diagnosis_value"), (Double) rs.getObject("confidence"), rs.getString("band"), Json.read(rs.getString("reason_codes"), STRINGS),
        rs.getString("outcome"), rs.getString("action_id"), rs.getString("escalation_task_id"), rs.getString("escalation_queue"), rs.getString("escalation_reason"),
        rs.getTimestamp("detected_at").toInstant());

    private final JdbcClient jdbc;

    public AnomalyRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Anomaly insert(Anomaly a) {
        jdbc.sql("""
            insert into autonomy_anomaly(id, tenant_id, order_id, anomaly_type, decision_record_id, diagnosis_value, confidence, band,
              reason_codes, outcome, action_id, escalation_task_id, escalation_queue, escalation_reason, detected_at)
            values (:id, :t, :o, :at, :rec, :val, :conf, :band, :reasons, :outcome, :actionId, :taskId, :queue, :reason, :when)
            """).param("id", a.id()).param("t", TenantContext.require()).param("o", a.orderId()).param("at", a.anomalyType())
            .param("rec", a.decisionRecordId()).param("val", a.diagnosisValue()).param("conf", a.confidence()).param("band", a.band())
            .param("reasons", Jsonb.of(a.reasonCodes())).param("outcome", a.outcome()).param("actionId", a.actionId()).param("taskId", a.escalationTaskId())
            .param("queue", a.escalationQueue()).param("reason", a.escalationReason()).param("when", Timestamp.from(a.detectedAt())).update();
        return a;
    }

    public Page<Anomaly> list(int offset, int limit) {
        List<Anomaly> items = jdbc.sql("select " + COLS + " from autonomy_anomaly where tenant_id = :t order by detected_at desc limit :limit offset :offset")
            .param("t", TenantContext.require()).param("limit", limit).param("offset", offset).query(MAPPER).list();
        long total = jdbc.sql("select count(*) from autonomy_anomaly where tenant_id = :t").param("t", TenantContext.require()).query(Long.class).single();
        return Page.of(items, total, offset, limit);
    }

    /** Whether this order already has an anomaly recorded within {@code within} (scan de-duplication, mirrors the exception dedupe pattern). */
    public boolean existsRecent(String orderId, Duration within, Instant now) {
        Long n = jdbc.sql("select count(*) from autonomy_anomaly where tenant_id = :t and order_id = :o and detected_at >= :since")
            .param("t", TenantContext.require()).param("o", orderId).param("since", Timestamp.from(now.minus(within))).query(Long.class).single();
        return n != null && n > 0;
    }

    /** Human escalations recorded today (UTC); feeds the stats endpoint's automation-rate calculation. */
    public int countEscalatedToday() {
        return jdbc.sql("select count(*) from autonomy_anomaly where tenant_id = :t and outcome = 'escalated' and detected_at >= date_trunc('day', now())")
            .param("t", TenantContext.require()).query(Integer.class).single();
    }

    public int countToday() {
        return jdbc.sql("select count(*) from autonomy_anomaly where tenant_id = :t and detected_at >= date_trunc('day', now())")
            .param("t", TenantContext.require()).query(Integer.class).single();
    }
}
