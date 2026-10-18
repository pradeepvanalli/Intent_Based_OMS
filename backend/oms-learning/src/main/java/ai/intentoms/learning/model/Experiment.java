package ai.intentoms.learning.model;

import java.time.Instant;
import java.util.Map;

/** A controlled exposure of a proposal to a slice of traffic (requirements doc §4, EX-02..EX-06). */
public record Experiment(String experimentId, String proposalId, ExperimentMode mode, double trafficShare, ExperimentStatus status,
                          Instant startAt, Instant endAt, Map<String, Object> stopRule, Map<String, Object> result, Instant createdAt) {}
