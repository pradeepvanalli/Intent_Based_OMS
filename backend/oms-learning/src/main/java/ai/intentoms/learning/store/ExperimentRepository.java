package ai.intentoms.learning.store;

import ai.intentoms.learning.model.Experiment;
import ai.intentoms.learning.model.ExperimentMode;
import ai.intentoms.learning.model.ExperimentStatus;
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
import java.util.Optional;

/** Persistence for {@code experiment} (EX-02..EX-06). */
@Repository
public class ExperimentRepository {
    private final JdbcClient jdbc;
    public ExperimentRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    private static final String COLS = "experiment_id, proposal_id, mode, traffic_share, status, start_at, end_at, stop_rule::text as stop_rule, result::text as result, created_at";

    public void insert(Experiment e) {
        jdbc.sql("""
            insert into experiment(experiment_id, tenant_id, proposal_id, mode, traffic_share, status, start_at, end_at, stop_rule, result, created_at)
            values (:id, :t, :prop, :mode, :share, :status, :start, :end, :stop, :result, :created)
            """)
            .param("id", e.experimentId()).param("t", TenantContext.require()).param("prop", e.proposalId()).param("mode", e.mode().name())
            .param("share", e.trafficShare()).param("status", e.status().name()).param("start", Timestamp.from(e.startAt()))
            .param("end", e.endAt() == null ? null : Timestamp.from(e.endAt())).param("stop", Jsonb.of(e.stopRule())).param("result", e.result() == null ? null : Jsonb.of(e.result()))
            .param("created", Timestamp.from(e.createdAt())).update();
    }

    /** The one active experiment for a proposal, if any (EX-06: one experiment per decision point per slice). */
    public Optional<Experiment> activeFor(String proposalId) {
        String t = TenantContext.require();
        return jdbc.sql("select " + COLS + " from experiment where tenant_id = :t and proposal_id = :p and status = 'RUNNING' order by start_at desc limit 1")
            .param("t", t).param("p", proposalId).query().listOfRows().stream().findFirst().map(ExperimentRepository::hydrate);
    }

    public Experiment get(String id) {
        String t = TenantContext.require();
        return jdbc.sql("select " + COLS + " from experiment where tenant_id = :t and experiment_id = :id").param("t", t).param("id", id)
            .query().listOfRows().stream().findFirst().map(ExperimentRepository::hydrate).orElseThrow(() -> new NotFoundException("Experiment " + id + " not found"));
    }

    public List<Experiment> running() {
        String t = TenantContext.require();
        return jdbc.sql("select " + COLS + " from experiment where tenant_id = :t and status = 'RUNNING'").param("t", t).query().listOfRows().stream().map(ExperimentRepository::hydrate).toList();
    }

    public void stop(String id, ExperimentStatus status, Map<String, Object> result, Instant endAt) {
        jdbc.sql("update experiment set status = :s, result = :r, end_at = :end where tenant_id = :t and experiment_id = :id")
            .param("s", status.name()).param("r", result == null ? null : Jsonb.of(result)).param("end", Timestamp.from(endAt)).param("t", TenantContext.require()).param("id", id).update();
    }

    private static Experiment hydrate(Map<String, Object> r) {
        String resultJson = (String) r.get("result");
        return new Experiment((String) r.get("experiment_id"), (String) r.get("proposal_id"), ExperimentMode.valueOf((String) r.get("mode")), (Double) r.get("traffic_share"),
            ExperimentStatus.valueOf((String) r.get("status")), ts(r.get("start_at")), ts(r.get("end_at")), Json.readMap((String) r.get("stop_rule")),
            resultJson == null ? null : Json.readMap(resultJson), ts(r.get("created_at")));
    }

    private static Instant ts(Object o) { return o == null ? null : ((Timestamp) o).toInstant(); }
}
