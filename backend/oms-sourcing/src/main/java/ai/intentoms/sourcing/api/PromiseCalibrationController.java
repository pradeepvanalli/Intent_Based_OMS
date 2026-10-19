package ai.intentoms.sourcing.api;

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
 * CX-01, measured: per lane, how far the on-time probability served now is from what happened, against the
 * requirement's 3-point target. The probability served is each delivery's probability before lane calibration plus
 * the correction {@link LaneKnowledge} serves on that lane today (0 while not promoted), so a promotion shows up at once
 * instead of waiting for the old samples to age out; {@code recordedResidualPoints} is the same measure against what
 * those orders were actually told at the time ({@code LANE_CALIBRATION_ERROR}). Every lane with
 * {@value LaneDeliveryLearner#MIN_RATE_SAMPLES}+ deliveries is listed; only lanes with {@code minSamples} (default 30)
 * count toward the target, since a handful of deliveries cannot show a 3-point error. Worst lanes first.
 */
@RestController
@RequestMapping("/api/v1/promise")
public class PromiseCalibrationController {
    public static final double TARGET_POINTS = 3.0;
    public static final int DEFAULT_MIN_SAMPLES = 30;
    private static final int SAMPLE_CAP = 200;

    private final LearnedFactRepository facts;
    private final LaneDeliverySampleRepository samples;
    private final LaneKnowledge lanes;

    public PromiseCalibrationController(LearnedFactRepository facts, LaneDeliverySampleRepository samples, LaneKnowledge lanes) {
        this.facts = facts;
        this.samples = samples;
        this.lanes = lanes;
    }

    @GetMapping("/calibration")
    @RequiresPermission({Permissions.LEARNING_READ, Permissions.ORDER_READ})
    public Map<String, Object> calibration(@RequestParam(defaultValue = "" + DEFAULT_MIN_SAMPLES) int minSamples) {
        Map<String, Double> offsets = numbers(FactKey.LANE_CALIBRATION_OFFSET);
        Map<String, Double> recorded = numbers(FactKey.LANE_CALIBRATION_ERROR);
        List<Map<String, Object>> out = new ArrayList<>();
        int counted = 0, within = 0;
        for (String lane : samples.lanesWithSamples()) {
            var s = samples.onTimeStats(lane, SAMPLE_CAP);
            double observed, served, correction = 0;
            long n;
            if (s.baseSamples() >= LaneDeliveryLearner.MIN_RATE_SAMPLES) {
                n = s.baseSamples();
                correction = lanes.servedCorrection(lane);
                observed = (double) s.onTimeOfBase() / n;
                served = Math.max(0, Math.min(1, s.meanBase() + correction));
            } else if (s.statedSamples() >= LaneDeliveryLearner.MIN_RATE_SAMPLES) {
                n = s.statedSamples();
                observed = (double) s.onTimeOfStated() / n;
                served = s.meanStated();
            } else continue;
            double points = points(observed - served);
            boolean counts = n >= minSamples;
            boolean ok = counts && Math.abs(points) <= TARGET_POINTS;
            if (counts) counted++;
            if (ok) within++;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lane", lane);
            m.put("residualPoints", points);
            m.put("servedProbability", Math.round(served * 1000) / 1000.0);
            m.put("observedOnTime", Math.round(observed * 1000) / 1000.0);
            m.put("servedCorrectionPoints", points(correction));
            if (recorded.containsKey(lane)) m.put("recordedResidualPoints", points(recorded.get(lane)));
            if (offsets.containsKey(lane)) m.put("learnedOffsetPoints", points(offsets.get(lane)));
            m.put("samples", n);
            m.put("countsTowardTarget", counts);
            m.put("withinTarget", ok);
            out.add(m);
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> !(Boolean) m.get("countsTowardTarget"))
            .thenComparingDouble(m -> -Math.abs((Double) m.get("residualPoints"))));
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("targetPoints", TARGET_POINTS);
        res.put("minSamples", minSamples);
        res.put("lanesMeasured", out.size());
        res.put("lanesCounted", counted);
        res.put("lanesWithinTarget", within);
        res.put("lanes", out);
        return res;
    }

    private Map<String, Double> numbers(FactKey key) {
        Map<String, Double> m = new HashMap<>();
        for (LearnedFact f : facts.activeByKey(SubjectType.LANE, key, 2_000)) if (f.value() instanceof TypedValue.NumberValue n) m.put(f.subjectId(), n.value());
        return m;
    }

    private static double points(double v) { return Math.round(v * 1000) / 10.0; }
}
