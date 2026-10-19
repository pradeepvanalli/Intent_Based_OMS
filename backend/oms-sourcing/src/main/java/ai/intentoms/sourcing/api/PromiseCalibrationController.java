package ai.intentoms.sourcing.api;

import ai.intentoms.learning.governance.ParameterResolver;
import ai.intentoms.learning.learner.LaneDeliveryLearner;
import ai.intentoms.learning.learner.LaneDeliverySampleRepository;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import ai.intentoms.sourcing.learning.LaneKnowledge;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CX-01, measured out of sample (audit B3-2): per lane, how far the on-time probability the calibration would have
 * served is from what then happened, against the requirement's 3-point target. A lane's most recent deliveries (up to
 * {@value #SAMPLE_CAP}) are split in time: the correction is learned on the earlier half exactly as the lane learner
 * learns it (observed on-time rate minus the mean probability before calibration), passed through the promoted range
 * and max step the way {@link LaneKnowledge} serves it (0 while not promoted), and measured on the later half, whose
 * outcomes it never saw. Measuring on the samples the correction was learned from would be 0 by construction.
 *
 * <p>Per lane the report also gives {@code toldResidualPoints}: on the later half, what those orders were actually told
 * when they were sourced against what happened, which is out of sample by nature. {@code clamped} says when the lane
 * needs a larger correction than the promoted range allows. Lanes whose later half has {@code minSamples} (default 30)
 * deliveries count toward the target; smaller ones are listed only. Worst counted lanes first.
 */
@RestController
@RequestMapping("/api/v1/promise")
public class PromiseCalibrationController {
    public static final double TARGET_POINTS = 3.0;
    public static final int DEFAULT_MIN_SAMPLES = 30;
    static final int SAMPLE_CAP = 200;

    private final LearnedFactRepository facts;
    private final LaneDeliverySampleRepository samples;
    private final LaneKnowledge lanes;

    public PromiseCalibrationController(LearnedFactRepository facts, LaneDeliverySampleRepository samples, LaneKnowledge lanes) {
        this.facts = facts;
        this.samples = samples;
        this.lanes = lanes;
    }

    /** On-time rate and mean told / pre-calibration probability over some deliveries. */
    record Window(int n, double observed, int stated, double meanStated, int based, double meanBase, double observedOfBase) {
        static Window of(List<LaneDeliverySampleRepository.Sample> s) {
            int onTime = 0, stated = 0, based = 0, onTimeOfBase = 0;
            double sumStated = 0, sumBase = 0;
            for (var x : s) {
                if (x.onTime()) onTime++;
                if (x.stated() != null) { stated++; sumStated += x.stated(); }
                if (x.base() != null) { based++; sumBase += x.base(); if (x.onTime()) onTimeOfBase++; }
            }
            return new Window(s.size(), s.isEmpty() ? 0 : (double) onTime / s.size(), stated, stated == 0 ? 0 : sumStated / stated,
                based, based == 0 ? 0 : sumBase / based, based == 0 ? 0 : (double) onTimeOfBase / based);
        }
    }

    @GetMapping("/calibration")
    @RequiresPermission({Permissions.LEARNING_READ, Permissions.ORDER_READ})
    public Map<String, Object> calibration(@RequestParam(defaultValue = "" + DEFAULT_MIN_SAMPLES) int minSamples) {
        Map<String, Double> offsets = numbers(FactKey.LANE_CALIBRATION_OFFSET);
        Map<String, Double> recorded = numbers(FactKey.LANE_CALIBRATION_ERROR);
        List<Map<String, Object>> out = new ArrayList<>();
        int counted = 0, within = 0, clampedLanes = 0;
        for (String lane : samples.lanesWithSamples()) {
            List<LaneDeliverySampleRepository.Sample> all = samples.recentSamples(lane, SAMPLE_CAP);
            int split = all.size() / 2;
            Window earlier = Window.of(all.subList(0, split)), later = Window.of(all.subList(split, all.size()));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lane", lane);
            double residual;
            long measured;
            if (earlier.based() >= LaneDeliveryLearner.MIN_RATE_SAMPLES && later.based() >= LaneDeliveryLearner.MIN_RATE_SAMPLES) {
                double needed = earlier.observedOfBase() - earlier.meanBase();
                ParameterResolver.Resolution served = lanes.correctionFor(needed);
                double correction = served.value() == null ? 0.0 : served.value();
                double predicted = Math.max(0, Math.min(1, later.meanBase() + correction));
                residual = later.observedOfBase() - predicted;
                measured = later.based();
                m.put("residualPoints", points(residual));
                m.put("servedProbability", round3(predicted));
                m.put("observedOnTime", round3(later.observedOfBase()));
                m.put("neededCorrectionPoints", points(needed));
                m.put("servedCorrectionPoints", points(correction));
                m.put("correctionSource", served.source());
                if (served.clamped()) {
                    clampedLanes++;
                    m.put("clamped", true);
                    m.put("clampReason", served.reason());
                }
            } else if (later.stated() >= LaneDeliveryLearner.MIN_RATE_SAMPLES) {
                // No pre-calibration probability recorded: only what the orders were told can be compared with what happened.
                residual = later.observed() - later.meanStated();
                measured = later.stated();
                m.put("residualPoints", points(residual));
                m.put("servedProbability", round3(later.meanStated()));
                m.put("observedOnTime", round3(later.observed()));
            } else continue;
            if (later.stated() >= LaneDeliveryLearner.MIN_RATE_SAMPLES) m.put("toldResidualPoints", points(later.observed() - later.meanStated()));
            m.put("servedNowCorrectionPoints", points(lanes.servedCorrection(lane)));
            if (recorded.containsKey(lane)) m.put("recordedResidualPoints", points(recorded.get(lane)));
            if (offsets.containsKey(lane)) m.put("learnedOffsetPoints", points(offsets.get(lane)));
            boolean counts = measured >= minSamples;
            boolean ok = counts && Math.abs(points(residual)) <= TARGET_POINTS;
            if (counts) counted++;
            if (ok) within++;
            m.put("samples", (long) all.size());
            m.put("learnedOn", (long) split);
            m.put("measuredOn", measured);
            m.put("countsTowardTarget", counts);
            m.put("withinTarget", ok);
            out.add(m);
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> !(Boolean) m.get("countsTowardTarget"))
            .thenComparingDouble(m -> -Math.abs((Double) m.get("residualPoints"))));
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("targetPoints", TARGET_POINTS);
        res.put("minSamples", minSamples);
        res.put("method", "out_of_sample");
        res.put("methodNote", "each lane's correction is learned on the older half of its recent deliveries and measured on the newer half, which it never saw; "
            + "toldResidualPoints is what those newer orders were told against what happened");
        res.put("lanesMeasured", out.size());
        res.put("lanesCounted", counted);
        res.put("lanesWithinTarget", within);
        res.put("lanesClamped", clampedLanes);
        res.put("lanes", out);
        return res;
    }

    private Map<String, Double> numbers(FactKey key) {
        Map<String, Double> m = new HashMap<>();
        for (LearnedFact f : facts.activeByKey(SubjectType.LANE, key, 2_000)) if (f.value() instanceof TypedValue.NumberValue n) m.put(f.subjectId(), n.value());
        return m;
    }

    private static double points(double v) { return Math.round(v * 1000) / 10.0; }
    private static double round3(double v) { return Math.round(v * 1000) / 1000.0; }
}
