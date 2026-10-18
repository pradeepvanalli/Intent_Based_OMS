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

    private static final String COLS = "experiment_id, proposal_id, mode, traffic_share, status, start_at, end_at, stop_rule::text as stop_rule, result::text as result, created_at, "
        + "target_ref, decision_name, candidate_version, baseline_version, created_by";

    /** Returns false when another experiment already runs on the same artifact in the same mode (EX-06). */
    public boolean insert(Experiment e) {
        return jdbc.sql("""
            insert into experiment(experiment_id, tenant_id, proposal_id, mode, traffic_share, status, start_at, end_at, stop_rule, result, created_at,
                                   target_ref, decision_name, candidate_version, baseline_version, created_by)
            values (:id, :t, :prop, :mode, :share, :status, :start, :end, :stop, :result, :created, :target, :decision, :cand, :base, :by)
            on conflict (tenant_id, target_ref, mode) where status = 'RUNNING' and target_ref is not null do nothing
            """)
            .param("id", e.experimentId()).param("t", TenantContext.require()).param("prop", e.proposalId()).param("mode", e.mode().name())
            .param("share", e.trafficShare()).param("status", e.status().name()).param("start", Timestamp.from(e.startAt()))
            .param("end", e.endAt() == null ? null : Timestamp.from(e.endAt())).param("stop", Jsonb.of(e.stopRule())).param("result", e.result() == null ? null : Jsonb.of(e.result()))
            .param("created", Timestamp.from(e.createdAt())).param("target", e.targetRef()).param("decision", e.decisionName())
            .param("cand", e.candidateVersion()).param("base", e.baselineVersion()).param("by", e.createdBy() == null ? "system" : e.createdBy())
            .update() == 1;
    }

    /** The one active experiment for a proposal, if any. */
    public Optional<Experiment> activeFor(String proposalId) {
        return many("select " + COLS + " from experiment where tenant_id = :t and proposal_id = :p and status = 'RUNNING' order by start_at desc limit 1", Map.of("p", proposalId))
            .stream().findFirst();
    }

    /** EX-06: the running experiment on an artifact in one mode (slice), if any. */
    public Optional<Experiment> runningOn(String targetRef, ExperimentMode mode) {
        return many("select " + COLS + " from experiment where tenant_id = :t and target_ref = :r and mode = :m and status = 'RUNNING' limit 1",
            Map.of("r", targetRef, "m", mode.name())).stream().findFirst();
    }

    public Experiment get(String id) {
        return find(id).orElseThrow(() -> new NotFoundException("Experiment " + id + " not found"));
    }

    public Optional<Experiment> find(String id) {
        return many("select " + COLS + " from experiment where tenant_id = :t and experiment_id = :id", Map.of("id", id)).stream().findFirst();
    }

    public List<Experiment> running() {
        return many("select " + COLS + " from experiment where tenant_id = :t and status = 'RUNNING' order by start_at", Map.of());
    }

    /** Running and recently finished experiments, newest first (Learning Console list). */
    public List<Experiment> recent(int limit) {
        return many("select " + COLS + " from experiment where tenant_id = :t order by (status = 'RUNNING') desc, start_at desc limit :lim", Map.of("lim", limit));
    }

    public List<Experiment> forProposal(String proposalId) {
        return many("select " + COLS + " from experiment where tenant_id = :t and proposal_id = :p order by start_at desc", Map.of("p", proposalId));
    }

    public void stop(String id, ExperimentStatus status, Map<String, Object> result, Instant endAt) {
        jdbc.sql("update experiment set status = :s, result = :r, end_at = :end where tenant_id = :t and experiment_id = :id")
            .param("s", status.name()).param("r", result == null ? null : Jsonb.of(result)).param("end", Timestamp.from(endAt)).param("t", TenantContext.require()).param("id", id).update();
    }

    /** Interim result while running (sequential test state), without stopping. */
    public void updateResult(String id, Map<String, Object> result) {
        jdbc.sql("update experiment set result = :r where tenant_id = :t and experiment_id = :id")
            .param("r", Jsonb.of(result)).param("t", TenantContext.require()).param("id", id).update();
    }

    private List<Experiment> many(String sql, Map<String, Object> params) {
        var spec = jdbc.sql(sql).param("t", TenantContext.require());
        for (var p : params.entrySet()) spec = spec.param(p.getKey(), p.getValue());
        return spec.query().listOfRows().stream().map(ExperimentRepository::hydrate).toList();
    }

    private static Experiment hydrate(Map<String, Object> r) {
        String resultJson = (String) r.get("result");
        return new Experiment((String) r.get("experiment_id"), (String) r.get("proposal_id"), ExperimentMode.valueOf((String) r.get("mode")), (Double) r.get("traffic_share"),
            ExperimentStatus.valueOf((String) r.get("status")), ts(r.get("start_at")), ts(r.get("end_at")), Json.readMap((String) r.get("stop_rule")),
            resultJson == null ? null : Json.readMap(resultJson), ts(r.get("created_at")), (String) r.get("target_ref"), (String) r.get("decision_name"),
            (Integer) r.get("candidate_version"), (Integer) r.get("baseline_version"), (String) r.get("created_by"));
    }

    private static Instant ts(Object o) { return o == null ? null : ((Timestamp) o).toInstant(); }
}
