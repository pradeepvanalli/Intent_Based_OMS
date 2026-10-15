package ai.intentoms.monitoring.api;

import ai.intentoms.monitoring.pipeline.AtRiskOrder;
import ai.intentoms.monitoring.pipeline.PipelineFilters;
import ai.intentoms.monitoring.pipeline.PipelineView;
import ai.intentoms.monitoring.pipeline.PipelineViewService;
import ai.intentoms.monitoring.sla.BreachRiskScorer;
import ai.intentoms.monitoring.sweeper.StuckOrderSweeper;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Control tower (UI contract item 8): pipeline heat-map, at-risk list and an admin trigger for the sweeps (FR-MON-01, 02, 05). */
@RestController
@RequestMapping("/api/v1/control-tower")
public class ControlTowerController {
    private final PipelineViewService pipeline;
    private final BreachRiskScorer risk;
    private final StuckOrderSweeper stuck;

    public ControlTowerController(PipelineViewService pipeline, BreachRiskScorer risk, StuckOrderSweeper stuck) {
        this.pipeline = pipeline;
        this.risk = risk;
        this.stuck = stuck;
    }

    @GetMapping("/pipeline")
    @RequiresPermission({Permissions.ANALYTICS_READ, Permissions.ORDER_READ})
    public PipelineView pipeline(@RequestParam(required = false) String channel, @RequestParam(required = false) String orderType, @RequestParam(required = false) String nodeId) {
        return pipeline.pipeline(new PipelineFilters(channel, orderType, nodeId));
    }

    @GetMapping("/at-risk")
    @RequiresPermission({Permissions.ANALYTICS_READ, Permissions.ORDER_READ})
    public List<AtRiskOrder> atRisk(@RequestParam(defaultValue = "50") int limit) { return pipeline.atRisk(limit); }

    /** Run the breach-risk and stuck-order sweeps now for the caller's tenant. */
    @PostMapping("/sweep")
    @RequiresPermission(Permissions.CONFIG_ADMIN)
    public Map<String, Object> sweep() {
        int scored = risk.sweepOnce();
        List<String> stuckOrders = stuck.sweepOnce();
        pipeline.invalidate();
        return Map.of("riskScored", scored, "stuckRaised", stuckOrders.size(), "stuckOrderIds", stuckOrders);
    }
}
