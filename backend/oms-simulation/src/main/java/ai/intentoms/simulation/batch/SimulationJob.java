package ai.intentoms.simulation.batch;

import ai.intentoms.simulation.whatif.ScenarioOverrides;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;

/**
 * A persisted batch simulation (table {@code simulation_job}; FR-SIM-02; UI contract item 13 {@code SimulationJob}
 * polled for {@code progress} and {@code results}).
 *
 * @param kind     batch | node_closure | carrier_outage | tariff_change
 * @param progress 0..1 share of the sampled orders processed
 * @param results  {@link SimulationMetrics#report} output; partial while RUNNING, null before the first commit
 */
public record SimulationJob(String id, String kind, String label, JobStatus status, double progress, Instant from, Instant to, ScenarioOverrides overrides, int sampleLimit,
                            String submittedBy, Instant submittedAt, Instant startedAt, Instant finishedAt, Map<String, Object> results, String error) {
    public SimulationJob {
        overrides = overrides == null ? ScenarioOverrides.none() : overrides;
        progress = Math.max(0.0, Math.min(1.0, progress));
    }

    /** UI alias for {@link #finishedAt}. */
    @JsonProperty("completedAt")
    public Instant completedAt() { return finishedAt; }

    public BatchRequest request() { return new BatchRequest(from, to, overrides, sampleLimit, label); }
}
