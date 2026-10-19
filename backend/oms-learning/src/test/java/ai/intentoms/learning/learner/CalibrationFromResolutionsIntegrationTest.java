package ai.intentoms.learning.learner;

import ai.intentoms.decision.definition.GoldenCaseRepository;
import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.model.EvaluationReport;
import ai.intentoms.decision.model.GoldenCase;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.learning.support.DecisionFabricTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DF-02: calibration curves form in practice. A golden set of 15 cases never fills a bin on its own (20 needed), but
 * with the live decisions people resolved (the model's raw confidence against the value the person settled on) the
 * bin fills and gets a shrunk, capped correction; bins that stay under the minimum get none.
 */
class CalibrationFromResolutionsIntegrationTest extends DecisionFabricTestBase {
    @Autowired ConfidenceCalibrator calibrator;
    @Autowired GoldenCaseRepository golden;
    @Autowired LearnedFactRepository facts;

    private static double mid(EvaluationReport.CalibrationBin b) { return (b.lower() + b.upper()) / 2; }

    @Test
    void fifteenGoldenCasesPlusFortyResolutionsGiveACurve() {
        String name = installProbe("df02");
        String gold = name + "_gold";
        var draft = studio.createDraft(name, probeYaml(name, "evaluation: { goldenSet: " + gold + ", minAccuracyInActBand: 0.0, maxCalibrationError: 1.0 }"));
        for (int i = 0; i < 15; i++) {
            Map<String, Object> input = order("g" + i, 100);
            DecisionResult sim = decisions.decide(name, input, DecisionOptions.simulation("golden_case", "g" + i, draft.version()));
            golden.upsert(new GoldenCase(gold, "case_" + i, input, Map.of("value", sim.value()), "model answer"));
        }
        studio.publish(name, draft.version(), "live", true);
        int v = liveVersion(name);

        // Where the golden cases sit, and that alone they are too few for a correction.
        EvaluationReport.CalibrationBin goldenBin = studio.evaluate(name, v).bins().stream().max(Comparator.comparingInt(EvaluationReport.CalibrationBin::count)).orElseThrow();
        assertThat(goldenBin.count()).isBetween(1, 15);
        calibrator.sweepOnce();
        assertThat(facts.active(SubjectType.DECISION, name, FactKey.CALIBRATION_CURVE)).as("15 golden cases alone").isEmpty();

        // 40 resolutions: enough in the golden cases' bin to reach 20 together (each source alone stays below), the rest
        // spread over other bins at fewer than 20 each. People agree with the model on half of them.
        int topUp = 20 - goldenBin.count() + 2;
        double inGoldenBin = mid(goldenBin);
        double[] elsewhere = inGoldenBin < 0.5 ? new double[]{0.85, 0.95, 0.75} : new double[]{0.15, 0.25, 0.35};
        Instant at = Instant.now().minus(Duration.ofDays(1));
        for (int i = 0; i < 40; i++) {
            double confidence = i < topUp ? inGoldenBin : elsewhere[i % 3];
            String human = i % 2 == 0 ? "approve" : "review";
            insertRecord(name, v, at, "approve", confidence, 20, Map.of(), Map.of("human_resolution", Map.of("value", human, "outcome", "override", "resolvedBy", "user:ops")), "system:test");
        }
        calibrator.sweepOnce();

        LearnedFact curve = facts.active(SubjectType.DECISION, name, FactKey.CALIBRATION_CURVE).orElseThrow();
        assertThat(curve.sampleSize()).as("15 golden cases and 40 resolutions").isEqualTo(55);
        List<String> entries = ((TypedValue.ListValue) curve.value()).values();
        String range = String.format(Locale.ROOT, "%.2f-%.2f:", goldenBin.lower(), goldenBin.upper());
        assertThat(entries).as("only the bin both sources filled").singleElement().satisfies(e -> {
            assertThat(String.valueOf(e)).startsWith(range);
            double correction = Double.parseDouble(String.valueOf(e).substring(range.length()));
            assertThat(Math.abs(correction)).isLessThanOrEqualTo(0.15 + 1e-9).isGreaterThan(0.0);
        });
        assertThat(facts.active(SubjectType.DECISION, name, FactKey.CALIBRATION_ERROR)).isPresent();
    }
}
