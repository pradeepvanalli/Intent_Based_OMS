package ai.intentoms.learning.model;

import java.time.Instant;
import java.util.Map;

/**
 * A controlled exposure of a proposal to a slice of traffic (requirements doc §4, EX-02..EX-06). {@code targetRef} is
 * the artifact the proposal changes ({@code decision:<name>}, {@code config:<key>}, {@code parameter:<ref>}, or
 * {@code tenant} for the persistent holdout). For a decision target, {@code candidateVersion} is the decision version
 * built from the changeset (a shadow version, or the version a canary slice sees) and {@code baselineVersion} the
 * version that was live when the experiment started.
 */
public record Experiment(String experimentId, String proposalId, ExperimentMode mode, double trafficShare, ExperimentStatus status,
                          Instant startAt, Instant endAt, Map<String, Object> stopRule, Map<String, Object> result, Instant createdAt,
                          String targetRef, String decisionName, Integer candidateVersion, Integer baselineVersion, String createdBy) {}
