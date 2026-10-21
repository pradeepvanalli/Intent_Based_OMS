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
 *
 * <p>DF-02 live source: while the family is live, every decision gets a calibration source on its record, not only
 * the ones a learned bin happens to cover. A deterministic provider's confidence can sit outside every learned bin
 * (no curve yet, or an edge value like the heuristic provider's fixed 0.0/0.55 before that bin reaches its minimum
 * sample count); rather than looking as if calibration were never consulted, such a record declines explicitly
 * ({@code source: "outside_curve"}, with the reason) so a reader can tell "consulted, no correction applied" from
 * "never asked".
 */
@Component
public class CurveConfidenceCalibration implements ConfidenceCalibration {
    public static final String FAMILY = "decision.calibration";
    static final double MAX_CORRECTION = 0.15;

    private final LearnedFactCache facts;
    private final ParameterResolver resolver;

    public CurveConfidenceCalibration(LearnedFactCache facts, ParameterResolver resolver) {
        this.facts = facts;
        this.resolver = resolver;
    }

    @Override
    public Optional<DecisionLearningHooks.Calibrated> calibrate(String decision, int version, double rawConfidence) {
        if (!resolver.isLive(FAMILY)) return Optional.empty();
        Optional<LearnedFact> curve = facts.active(SubjectType.DECISION, decision, FactKey.CALIBRATION_CURVE);
        if (curve.isPresent() && curve.get().value() instanceof TypedValue.ListValue lv) {
            for (String bin : lv.values()) {
                Bin b = Bin.parse(bin);
                if (b == null || rawConfidence < b.lower() || rawConfidence > b.upper()) continue;
                // Safety net for curves written before the per-bin minimum existed: never move confidence by more than this.
                double correction = Math.max(-MAX_CORRECTION, Math.min(MAX_CORRECTION, b.correction()));
                double calibrated = Math.max(0.0, Math.min(1.0, rawConfidence + correction));
                Map<String, Object> source = new LinkedHashMap<>();
                source.put("family", FAMILY);
                source.put("source", "curve");
                source.put("factVersion", curve.get().version());
                source.put("bin", bin);
                source.put("correction", correction);
                source.put("learner", curve.get().learner());
                source.put("decisionVersion", version);
                return Optional.of(new DecisionLearningHooks.Calibrated(calibrated, source));
            }
        }
        Map<String, Object> declined = new LinkedHashMap<>();
        declined.put("family", FAMILY);
        declined.put("source", "outside_curve");
        declined.put("decisionVersion", version);
        declined.put("reason", curve.isEmpty() || !(curve.get().value() instanceof TypedValue.ListValue)
            ? "no calibration curve learned yet for this decision"
            : "raw confidence " + rawConfidence + " is outside every learned bin");
        return Optional.of(new DecisionLearningHooks.Calibrated(rawConfidence, declined));
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
