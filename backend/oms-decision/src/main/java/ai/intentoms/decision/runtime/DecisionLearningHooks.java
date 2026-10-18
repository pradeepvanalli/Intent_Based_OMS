package ai.intentoms.decision.runtime;

import ai.intentoms.decision.model.DecisionOptions;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Where the self-optimizing layer meets the decision fabric, without oms-decision depending on oms-learning. The
 * runtime calls these at fixed points of every LIVE or SHADOW evaluation; simulation, replay and golden-set runs never
 * see them, so a replay of a recorded input reproduces the recorded answer and calibration is measured on raw model
 * confidence. The default does nothing; oms-learning provides the real implementation, and each method there is
 * governed (a learner that is off, paused, at L0 or not promoted for live use contributes nothing).
 */
public interface DecisionLearningHooks {
    DecisionLearningHooks NOOP = new DecisionLearningHooks() {};

    /**
     * CI-05 / XP-03 / NF-04: learned facts merged into the input before it is hashed and stored. Returns the input to
     * use and the facts (subject, key, version, value) it took from, recorded on the decision as {@code learnedFacts}.
     */
    default Contribution contribute(String decision, Map<String, Object> input, DecisionOptions opts) { return Contribution.none(input); }

    /** DF-02: the calibrated confidence for a raw L1 confidence, or the raw value when no live calibration applies. */
    default Calibrated calibrate(String decision, int version, double rawConfidence) { return new Calibrated(rawConfidence, null); }

    /** DF-05: a provider to try first for this decision, when a promoted routing change says so. */
    default Optional<String> preferredProvider(String decision, int version) { return Optional.empty(); }

    /**
     * EX-03 / EX-04: which version a subject sees while an experiment runs: a sticky canary slice gets the candidate,
     * a holdout slice the baseline. Empty means normal live/canary resolution.
     */
    default Optional<Route> route(String decision, String routingKey, int resolvedVersion) { return Optional.empty(); }

    record Contribution(Map<String, Object> input, List<Map<String, Object>> factsUsed) {
        public static Contribution none(Map<String, Object> input) { return new Contribution(input, List.of()); }
    }

    record Calibrated(double confidence, Map<String, Object> source) {}

    /** {@code arm} is "canary", "control" or "holdout"; {@code version} the definition version to evaluate. */
    record Route(String experimentId, String arm, int version) {}
}
