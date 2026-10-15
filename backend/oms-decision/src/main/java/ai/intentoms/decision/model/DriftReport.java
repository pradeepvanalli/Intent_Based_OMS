package ai.intentoms.decision.model;

import java.util.List;
import java.util.Map;

/** Weekly calibration and drift check per decision version (GR-12). */
public record DriftReport(String decision, int version, int labelled, double calibrationError, double maxCalibrationError, List<EvaluationReport.CalibrationBin> bins,
                          Map<String, Double> bandMixThisWeek, Map<String, Double> bandMixLastWeek, double maxBandShiftPoints, boolean drift, List<String> reasons) {}
