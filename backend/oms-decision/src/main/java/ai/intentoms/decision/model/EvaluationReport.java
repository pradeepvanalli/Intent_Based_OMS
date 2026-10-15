package ai.intentoms.decision.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Golden-set evaluation (GR-11). {@code accuracyInActBand} is null when no case landed in the act band; the gate then
 * falls back to overall accuracy (there is nothing autonomous to gate, but a wrong definition must still not publish).
 * Calibration error = count-weighted mean of |mean confidence - accuracy| over 10 confidence bins.
 */
public record EvaluationReport(String decision, int version, String goldenSet, int cases, int correct, double accuracy, int actBandCases, int actBandCorrect,
                               Double accuracyInActBand, double calibrationError, List<CalibrationBin> bins, Map<String, Integer> bandMix, boolean passed,
                               List<String> gateFailures, List<CaseOutcome> failures, double costUsd, Instant evaluatedAt) {
    public record CalibrationBin(double lower, double upper, int count, double meanConfidence, double accuracy) {}
    public record CaseOutcome(String caseId, Object expected, Object actual, Band band, double confidence) {}
}
