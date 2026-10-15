package ai.intentoms.decision.model;

/** Evaluation gate (GR-11): golden set name plus minimum accuracy in the act band and maximum calibration error. */
public record EvaluationSpec(String goldenSet, double minAccuracyInActBand, double maxCalibrationError) {}
