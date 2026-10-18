package ai.intentoms.learning.hooks;

import ai.intentoms.decision.runtime.DecisionLearningHooks;

import java.util.Optional;

/** DF-02: maps a raw L1 confidence to observed accuracy for decisions whose calibration is promoted for live use. */
public interface ConfidenceCalibration {
    Optional<DecisionLearningHooks.Calibrated> calibrate(String decision, int version, double rawConfidence);
}
