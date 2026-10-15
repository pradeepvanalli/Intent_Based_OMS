package ai.intentoms.workflow.bulk;

import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.jdbc.Page;
import ai.intentoms.platform.json.Json;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** SQL for {@code bulk_operation} (FR-WF-07). */
@Repository
public class BulkOperationRepository {
    private static final String COLUMNS = """
        id, action, params::text as params, subject_ids::text as subject_ids, dry_run, per_second, status, total, done, failed, results::text as results, error,
        submitted_by, created_at, started_at, finished_at, rolled_back_at
        """;
    private static final RowMapper<BulkOperation> MAPPER = (rs, i) -> new BulkOperation(rs.getString("id"), rs.getString("action"),
        Json.readMap(rs.getString("params")), Json.readList(rs.getString("subject_ids")).stream().map(String::valueOf).toList(), rs.getBoolean("dry_run"),
        rs.getInt("per_second"), rs.getString("status"), rs.getInt("total"), rs.getInt("done"), rs.getInt("failed"), results(rs.getString("results")),
        rs.getString("error"), rs.getString("submitted_by"), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("started_at")),
        instant(rs.getTimestamp("finished_at")), instant(rs.getTimestamp("rolled_back_at")));

    private final JdbcClient jdbc;

    public BulkOperationRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void insert(String tenant, BulkOperation b) {
        jdbc.sql("""
            insert into bulk_operation(id, tenant_id, action, params, subject_ids, dry_run, per_second, status, total, done, failed, results, submitted_by, created_at)
            values (:id, :t, :action, :params, :subjects, :dry, :ps, :status, :total, 0, 0, '[]'::jsonb, :by, :created)
            """)
            .param("id", b.id()).param("t", tenant).param("action", b.action()).param("params", Jsonb.of(b.params())).param("subjects", Jsonb.of(b.subjectIds()))
            .param("dry", b.dryRun()).param("ps", b.perSecond()).param("status", b.status()).param("total", b.total()).param("by", b.submittedBy())
            .param("created", Timestamp.from(b.createdAt())).update();
    }

    public Optional<BulkOperation> find(String tenant, String id) {
        return jdbc.sql("select " + COLUMNS + " from bulk_operation where tenant_id = :t and id = :id").param("t", tenant).param("id", id).query(MAPPER).optional();
    }

    public Optional<BulkOperation> lock(String tenant, String id) {
        return jdbc.sql("select " + COLUMNS + " from bulk_operation where tenant_id = :t and id = :id for update").param("t", tenant).param("id", id).query(MAPPER).optional();
    }

    public Page<BulkOperation> list(String tenant, int offset, int limit) {
        long total = jdbc.sql("select count(*) from bulk_operation where tenant_id = :t").param("t", tenant).query(Long.class).single();
        List<BulkOperation> items = jdbc.sql("select " + COLUMNS + " from bulk_operation where tenant_id = :t order by created_at desc offset :o limit :l")
            .param("t", tenant).param("o", offset).param("l", limit).query(MAPPER).list();
        return Page.of(items, total, offset, limit);
    }

    public void markStarted(String tenant, String id, Instant now) {
        jdbc.sql("update bulk_operation set status = :s, started_at = :now where tenant_id = :t and id = :id")
            .param("s", BulkStatus.RUNNING).param("now", Timestamp.from(now)).param("t", tenant).param("id", id).update();
    }

    /** Append one subject result and advance the counters. */
    public void appendResult(String tenant, String id, Map<String, Object> result, boolean ok) {
        jdbc.sql("update bulk_operation set results = results || :r, done = done + 1, failed = failed + :f where tenant_id = :t and id = :id")
            .param("r", Jsonb.of(List.of(result))).param("f", ok ? 0 : 1).param("t", tenant).param("id", id).update();
    }

    public void finish(String tenant, String id, String status, String error, Instant now) {
        jdbc.sql("update bulk_operation set status = :s, error = :e, finished_at = :now where tenant_id = :t and id = :id")
            .param("s", status).param("e", error).param("now", Timestamp.from(now)).param("t", tenant).param("id", id).update();
    }

    public void setStatus(String tenant, String id, String status) {
        jdbc.sql("update bulk_operation set status = :s where tenant_id = :t and id = :id").param("s", status).param("t", tenant).param("id", id).update();
    }

    public void rolledBack(String tenant, String id, List<Map<String, Object>> results, String status, Instant now) {
        jdbc.sql("update bulk_operation set results = :r, status = :s, rolled_back_at = :now where tenant_id = :t and id = :id")
            .param("r", Jsonb.of(results)).param("s", status).param("now", Timestamp.from(now)).param("t", tenant).param("id", id).update();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> results(String json) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : Json.readList(json)) out.add(new LinkedHashMap<>((Map<String, Object>) o));
        return out;
    }

    private static Instant instant(Timestamp t) { return t == null ? null : t.toInstant(); }
}
