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

    /** As {@link #calibrate(String, int, double)}, for the subject named by {@code routingKey} (EX-04 holdout). */
    default Calibrated calibrate(String decision, int version, double rawConfidence, String routingKey) { return calibrate(decision, version, rawConfidence); }

    /** DF-05: a provider to try first for this decision, when a promoted routing change says so. */
    default Optional<String> preferredProvider(String decision, int version) { return Optional.empty(); }

    /** As {@link #preferredProvider(String, int)}, for the subject named by {@code routingKey} (EX-04 holdout). */
    default Optional<String> preferredProvider(String decision, int version, String routingKey) { return preferredProvider(decision, version); }

    /**
     * EX-03 / EX-04: which version a subject sees while an experiment runs: a sticky canary slice gets the candidate,
     * a holdout slice the baseline. Empty means normal live/canary resolution.
     */
    default Optional<Route> route(String decision, String routingKey, int resolvedVersion) { return Optional.empty(); }

    /**
     * EX-01 / requirements §11 ("exploration floor per arm; clipped estimators"): for a LIVE decision that acts, which arm
     * to apply and the true probability of applying it. On a governed share of decisions another allowed arm is applied
     * (probability share / other arms), otherwise the decision's own choice (probability 1 - share), so off-policy
     * evaluation has support for the arms the decision would not pick. Empty means no exploration: the choice is applied
     * with probability 1. The runtime asks only for T0 decisions that acted on a model answer, never in shadow, replay or
     * simulation.
     */
    default Optional<Exploration> explore(String decision, ai.intentoms.decision.model.Tier tier, List<String> arms, String chosen, String routingKey) { return Optional.empty(); }

    /** The arm to apply, its true probability, the exploration share and the seed of the draw (stored for replay). */
    record Exploration(String applied, double propensity, double share, long seed) {
        public boolean explored(String chosen) { return applied != null && !applied.equals(chosen); }
    }

    record Contribution(Map<String, Object> input, List<Map<String, Object>> factsUsed) {
        public static Contribution none(Map<String, Object> input) { return new Contribution(input, List.of()); }
    }

    record Calibrated(double confidence, Map<String, Object> source) {}

    /** {@code arm} is "canary", "control" or "holdout"; {@code version} the definition version to evaluate. */
    record Route(String experimentId, String arm, int version) {}
}
