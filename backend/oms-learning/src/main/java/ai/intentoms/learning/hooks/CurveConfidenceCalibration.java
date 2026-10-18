package ai.intentoms.learning.hooks;

import ai.intentoms.decision.runtime.DecisionLearningHooks;
import ai.intentoms.learning.governance.ParameterResolver;
import ai.intentoms.learning.learner.ConfidenceCalibrator;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.LearnedFactCache;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * DF-02 on the live path: remaps a decision's raw L1 confidence with the calibration curve the
 * {@link ConfidenceCalibrator} learned from its golden set ({@code CALIBRATION_CURVE}, one {@code "<lower>-<upper>:<correction>"}
 * entry per confidence bin), so "90% sure" means right about 90% of the time. Applies only while the
 * {@code decision.calibration} family is promoted for live use and the calibrator is on and above L0; the runtime records
 * the raw confidence and this source on the decision. Bands are then applied to the calibrated value.
 */
@Component
public class CurveConfidenceCalibration implements ConfidenceCalibration {
    public static final String FAMILY = "decision.calibration";

    private final LearnedFactCache facts;
    private final ParameterResolver resolver;

    public CurveConfidenceCalibration(LearnedFactCache facts, ParameterResolver resolver) {
        this.facts = facts;
        this.resolver = resolver;
    }

    @Override
    public Optional<DecisionLearningHooks.Calibrated> calibrate(String decision, int version, double rawConfidence) {
        Optional<LearnedFact> curve = facts.active(SubjectType.DECISION, decision, FactKey.CALIBRATION_CURVE);
        if (curve.isEmpty() || !(curve.get().value() instanceof TypedValue.ListValue lv) || !resolver.isLive(FAMILY)) return Optional.empty();
        for (String bin : lv.values()) {
            Bin b = Bin.parse(bin);
            if (b == null || rawConfidence < b.lower() || rawConfidence > b.upper()) continue;
            double calibrated = Math.max(0.0, Math.min(1.0, rawConfidence + b.correction()));
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("family", FAMILY);
            source.put("factVersion", curve.get().version());
            source.put("bin", bin);
            source.put("correction", b.correction());
            source.put("learner", curve.get().learner());
            source.put("decisionVersion", version);
            return Optional.of(new DecisionLearningHooks.Calibrated(calibrated, source));
        }
        return Optional.empty();
    }

    record Bin(double lower, double upper, double correction) {
        /** Parses {@code "0.80-0.90:-0.050"}; returns null for anything malformed. */
        static Bin parse(String s) {
            try {
                int colon = s.lastIndexOf(':');
                int dash = s.indexOf('-', 1);
                if (colon < 0 || dash < 0 || dash > colon) return null;
                return new Bin(Double.parseDouble(s.substring(0, dash)), Double.parseDouble(s.substring(dash + 1, colon)), Double.parseDouble(s.substring(colon + 1)));
            } catch (RuntimeException e) { return null; }
        }
    }
}
