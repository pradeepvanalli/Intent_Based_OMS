package ai.intentoms.learning.learner;

import ai.intentoms.decision.model.EvaluationReport.CalibrationBin;
import ai.intentoms.learning.hooks.CurveConfidenceCalibrationAccess;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** DF-01's objective: highest act-band accuracy whose expected escalations fit the budget. */
class BandThresholdObjectiveTest {
    private static final List<CalibrationBin> BINS = List.of(
        new CalibrationBin(0.5, 0.6, 20, 0.55, 0.80),
        new CalibrationBin(0.6, 0.7, 20, 0.65, 0.88),
        new CalibrationBin(0.7, 0.8, 20, 0.75, 0.93),
        new CalibrationBin(0.8, 0.9, 20, 0.85, 0.97));

    @Test
    void withoutABudgetTheLowestThresholdWhereEveryBinClearsWins() {
        var c = BandThresholdLearner.choose(BINS, 0.85, t -> 0, -1, 10).orElseThrow();
        assertThat(c.threshold()).isEqualTo(0.6);
    }

    @Test
    void aBudgetCapsHowFarTheThresholdCanRiseForAccuracy() {
        // escalations/day rise as the act threshold rises: 0.5 -> 0, 0.6 -> 20, 0.7 -> 40, 0.8 -> 60
        var tight = BandThresholdLearner.choose(BINS, 0.85, t -> Math.round((t - 0.5) * 200), 45, 10).orElseThrow();
        assertThat(tight.threshold()).isEqualTo(0.7);
        var loose = BandThresholdLearner.choose(BINS, 0.85, t -> Math.round((t - 0.5) * 200), 100, 10).orElseThrow();
        assertThat(loose.threshold()).isEqualTo(0.8);
        assertThat(BandThresholdLearner.choose(BINS, 0.99, t -> 0, 100, 10)).isEmpty();
    }

    @Test
    void calibrationBinsParse() {
        assertThat(CurveConfidenceCalibrationAccess.parse("0.80-0.90:-0.050")).containsExactly(0.8, 0.9, -0.05);
        assertThat(CurveConfidenceCalibrationAccess.parse("garbage")).isNull();
    }
}
