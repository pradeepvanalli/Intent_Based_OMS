package ai.intentoms.learning.api;

import ai.intentoms.learning.service.RewardBaselineService;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * §7 normalisation, readable: the scale every reward term is divided by for this tenant, with its method (identity,
 * p95 of a baseline, documented default), its source (holdout, logged episodes, default) and the reason. Recomputed by
 * the daily job and by {@code POST /api/v1/learning/jobs/reward-baseline/run}.
 */
@RestController
@RequestMapping("/api/v1/learning")
public class RewardBaselineController {
    private final RewardBaselineService baseline;

    public RewardBaselineController(RewardBaselineService baseline) { this.baseline = baseline; }

    @GetMapping("/reward-baseline")
    @RequiresPermission(Permissions.LEARNING_READ)
    public Map<String, Object> rewardBaseline() { return baseline.effective(); }
}
