package ai.intentoms.sourcing.api;

import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
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
 * CX-01, measured: per lane, how far the on-time probability orders were given is from what happened, after any
 * calibration ({@code LANE_CALIBRATION_ERROR}), against the requirement's 3-point target, with the learned offset next
 * to it. Worst lanes first.
 */
@RestController
@RequestMapping("/api/v1/promise")
public class PromiseCalibrationController {
    public static final double TARGET_POINTS = 3.0;
    private final LearnedFactRepository facts;

    public PromiseCalibrationController(LearnedFactRepository facts) { this.facts = facts; }

    @GetMapping("/calibration")
    @RequiresPermission({Permissions.LEARNING_READ, Permissions.ORDER_READ})
    public Map<String, Object> calibration(@RequestParam(defaultValue = "10") int minSamples) {
        Map<String, Double> offsets = new HashMap<>();
        for (LearnedFact f : facts.activeByKey(SubjectType.LANE, FactKey.LANE_CALIBRATION_OFFSET, 2_000)) {
            if (f.value() instanceof TypedValue.NumberValue n) offsets.put(f.subjectId(), n.value());
        }
        List<Map<String, Object>> lanes = new ArrayList<>();
        int within = 0;
        for (LearnedFact f : facts.activeByKey(SubjectType.LANE, FactKey.LANE_CALIBRATION_ERROR, 2_000)) {
            if (!(f.value() instanceof TypedValue.NumberValue n) || f.sampleSize() < minSamples) continue;
            double points = Math.round(n.value() * 1000) / 10.0;
            boolean ok = Math.abs(points) <= TARGET_POINTS;
            if (ok) within++;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lane", f.subjectId());
            m.put("residualPoints", points);
            m.put("withinTarget", ok);
            m.put("samples", f.sampleSize());
            if (offsets.containsKey(f.subjectId())) m.put("learnedOffsetPoints", Math.round(offsets.get(f.subjectId()) * 1000) / 10.0);
            m.put("version", f.version());
            lanes.add(m);
        }
        lanes.sort(Comparator.comparingDouble((Map<String, Object> m) -> -Math.abs((Double) m.get("residualPoints"))));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("targetPoints", TARGET_POINTS);
        out.put("lanesMeasured", lanes.size());
        out.put("lanesWithinTarget", within);
        out.put("lanes", lanes);
        return out;
    }
}
