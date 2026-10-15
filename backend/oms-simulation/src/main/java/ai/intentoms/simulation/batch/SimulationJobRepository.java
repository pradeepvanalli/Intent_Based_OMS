package ai.intentoms.simulation.batch;

import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.simulation.whatif.ScenarioOverrides;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** {@code simulation_job} persistence (FR-SIM-02). Every statement is scoped to {@link TenantContext#require()}. */
@Repository
public class SimulationJobRepository {
    private static final String COLS = "id, kind, label, request::text as request, status, progress, results::text as results, error, cancel_requested, submitted_by, created_at, started_at, finished_at";

    private final JdbcClient jdbc;

    public SimulationJobRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<SimulationJob> MAPPER = (rs, i) -> {
        Map<String, Object> request = Json.readMap(rs.getString("request"));
        Instant from = request.get("from") == null ? null : Instant.parse(request.get("from").toString());
        Instant to = request.get("to") == null ? null : Instant.parse(request.get("to").toString());
        ScenarioOverrides overrides = request.get("overrides") == null ? ScenarioOverrides.none() : Json.convert(request.get("overrides"), ScenarioOverrides.class);
        int sampleLimit = request.get("sampleLimit") instanceof Number n ? n.intValue() : 0;
        String results = rs.getString("results");
        return new SimulationJob(rs.getString("id"), rs.getString("kind"), rs.getString("label"), JobStatus.valueOf(rs.getString("status")), rs.getDouble("progress"), from, to, overrides,
            sampleLimit, rs.getString("submitted_by"), rs.getTimestamp("created_at").toInstant(), ts(rs.getTimestamp("started_at")), ts(rs.getTimestamp("finished_at")),
            results == null ? null : Json.readMap(results), rs.getString("error"));
    };

    private static Instant ts(Timestamp t) { return t == null ? null : t.toInstant(); }

    public void insert(SimulationJob job) {
        Map<String, Object> request = Map.of("from", job.from().toString(), "to", job.to().toString(), "overrides", job.overrides(), "sampleLimit", job.sampleLimit());
        jdbc.sql("insert into simulation_job(id, tenant_id, kind, label, request, status, progress, submitted_by, created_at) values (:id, :t, :k, :l, :r, :s, :p, :by, :at)")
            .param("id", job.id()).param("t", TenantContext.require()).param("k", job.kind()).param("l", job.label()).param("r", Jsonb.of(request)).param("s", job.status().name())
            .param("p", job.progress()).param("by", job.submittedBy()).param("at", Timestamp.from(job.submittedAt())).update();
    }

    public Optional<SimulationJob> find(String id) {
        return jdbc.sql("select " + COLS + " from simulation_job where tenant_id = :t and id = :id").param("t", TenantContext.require()).param("id", id).query(MAPPER).optional();
    }

    public List<SimulationJob> list(int offset, int limit) {
        return jdbc.sql("select " + COLS + " from simulation_job where tenant_id = :t order by created_at desc limit :l offset :o")
            .param("t", TenantContext.require()).param("l", limit).param("o", offset).query(MAPPER).list();
    }

    public long count() {
        return jdbc.sql("select count(*) from simulation_job where tenant_id = :t").param("t", TenantContext.require()).query(Long.class).single();
    }

    public void markStarted(String id, Instant at) {
        jdbc.sql("update simulation_job set status = :s, started_at = :at where tenant_id = :t and id = :id")
            .param("s", JobStatus.RUNNING.name()).param("at", Timestamp.from(at)).param("t", TenantContext.require()).param("id", id).update();
    }

    /** Progressive commit while RUNNING (FR-SIM-02: the UI polls progress). */
    public void progress(String id, double progress, Map<String, Object> partialResults) {
        jdbc.sql("update simulation_job set progress = :p, results = :r where tenant_id = :t and id = :id")
            .param("p", progress).param("r", partialResults == null ? null : Jsonb.of(partialResults)).param("t", TenantContext.require()).param("id", id).update();
    }

    public void finish(String id, JobStatus status, double progress, Map<String, Object> results, String error, Instant at) {
        jdbc.sql("update simulation_job set status = :s, progress = :p, results = :r, error = :e, finished_at = :at where tenant_id = :t and id = :id")
            .param("s", status.name()).param("p", progress).param("r", results == null ? null : Jsonb.of(results)).param("e", error).param("at", Timestamp.from(at))
            .param("t", TenantContext.require()).param("id", id).update();
    }

    /** Flags a cancel; the worker observes it at its next checkpoint. Returns false when the job is already terminal. */
    public boolean requestCancel(String id) {
        return jdbc.sql("update simulation_job set cancel_requested = true where tenant_id = :t and id = :id and status in ('QUEUED','RUNNING')")
            .param("t", TenantContext.require()).param("id", id).update() > 0;
    }

    public boolean cancelRequested(String id) {
        return jdbc.sql("select cancel_requested from simulation_job where tenant_id = :t and id = :id").param("t", TenantContext.require()).param("id", id)
            .query(Boolean.class).optional().orElse(false);
    }
}
