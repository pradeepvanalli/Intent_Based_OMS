package ai.intentoms.learning.hooks;

import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.learning.governance.ParameterResolver;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.LearnedFactCache;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.learning.support.DecisionFabricTestBase;
import ai.intentoms.platform.ids.Ids;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DF-02 live source (audit 4): {@code decision.calibration} was live on the demo tenant, yet no live record carried
 * a calibration source, because the heuristic provider's confidence sits outside every learned bin — a reader could
 * not tell "calibration consulted, no bin covered this confidence" from "calibration never asked at all". Fixed:
 * while the family is live, a decision whose confidence falls outside every bin (or that has no curve yet) still
 * gets a {@code calibration} source on the record, explicitly declining ({@code source: "outside_curve"}, with a
 * reason), instead of nothing.
 */
class CurveConfidenceCalibrationDeclineIntegrationTest extends DecisionFabricTestBase {
    @Autowired LearnedFactRepository facts;
    @Autowired LearnedFactCache factCache;
    @Autowired ParameterResolver parameters;

    private static final String TESTER = "user:tester";

    @Test
    @SuppressWarnings("unchecked")
    void aConfidenceOutsideEveryLearnedBinStillGetsAnExplicitCalibrationSource() {
        String name = installProbe("df02_decline");
        // Sanity: before the family is live, no calibration metadata at all (not consulted).
        DecisionResult before = decideLive(name, "ord_b" + Ids.shortId(), 100, null);
        assertThat(before.details()).doesNotContainKey("rawConfidence").doesNotContainKey("calibration");

        // A curve with a deliberate gap at [0.50, 0.70): the heuristic provider's confidence on this probe (0.55-0.60,
        // see HeuristicDecisionProvider) falls in that gap, so no bin covers it.
        Instant now = Instant.now();
        facts.insert(new LearnedFact(Ids.prefixed("fact"), SubjectType.DECISION, name, FactKey.CALIBRATION_CURVE,
            new TypedValue.ListValue(List.of("0.00-0.50:+0.000", "0.70-1.00:+0.000")), 1.0, 40, "confidence_calibrator", 1, now, null, now));
        factCache.invalidateAll();
        parameters.promote(CurveConfidenceCalibration.FAMILY, "confidence_calibrator", 0.0, 1.0, null, null, TESTER);
        try {
            DecisionResult after = decideLive(name, "ord_a" + Ids.shortId(), 100, null);
            double raw = ((Number) after.details().get("rawConfidence")).doubleValue();
            assertThat(raw).as("the heuristic provider's confidence sits in the curve's gap").isBetween(0.50, 0.70);
            assertThat(after.confidence()).as("no bin matched, so confidence is unchanged").isEqualTo(raw);

            Map<String, Object> calibration = (Map<String, Object>) after.details().get("calibration");
            assertThat(calibration).as("calibration was consulted and says so, even though it declined")
                .containsEntry("family", CurveConfidenceCalibration.FAMILY)
                .containsEntry("source", "outside_curve");
            assertThat(String.valueOf(calibration.get("reason"))).contains("outside every learned bin");
        } finally {
            parameters.revoke(CurveConfidenceCalibration.FAMILY, TESTER);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void noCurveAtAllStillGetsAnExplicitCalibrationSourceOnceTheFamilyIsLive() {
        String name = installProbe("df02_nocurve");
        parameters.promote(CurveConfidenceCalibration.FAMILY, "confidence_calibrator", 0.0, 1.0, null, null, TESTER);
        try {
            DecisionResult r = decideLive(name, "ord_" + Ids.shortId(), 100, null);
            Map<String, Object> calibration = (Map<String, Object>) r.details().get("calibration");
            assertThat(calibration).containsEntry("source", "outside_curve");
            assertThat(String.valueOf(calibration.get("reason"))).contains("no calibration curve learned yet");
        } finally {
            parameters.revoke(CurveConfidenceCalibration.FAMILY, TESTER);
        }
    }
}
