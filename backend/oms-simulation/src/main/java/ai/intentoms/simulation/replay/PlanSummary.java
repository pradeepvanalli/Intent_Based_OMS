package ai.intentoms.simulation.replay;

import ai.intentoms.sourcing.model.PlanPackage;
import ai.intentoms.sourcing.model.SourcingPlan;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The comparable economics of a sourcing plan (FR-SIM-01 sourcing diff, FR-SIM-02 cost / split / on-time / margin).
 *
 * @param source recorded (from the {@code order.sourced} ledger payload) | simulated (a fresh {@code SourcingService.options} plan)
 */
public record PlanSummary(String planId, String policy, int packages, List<String> nodeIds, BigDecimal totalCost, Double marginRatio, Double onTimeProbability, String latestEta,
                          boolean meetsMarginFloor, String source) {
    public static final String RECORDED = "recorded";
    public static final String SIMULATED = "simulated";

    public PlanSummary { nodeIds = nodeIds == null ? List.of() : List.copyOf(nodeIds); }

    public static PlanSummary recorded(RebuiltOrder.SourcedPlan p) {
        if (p == null) return null;
        return new PlanSummary(p.planId(), p.policy(), p.packages(), p.nodeIds(), p.totalCost(), p.marginRatio(), p.onTimeProbability(), p.latestEta(),
            p.marginRatio() == null || p.marginRatio() >= 0, RECORDED);
    }

    public static PlanSummary simulated(SourcingPlan p) {
        if (p == null) return null;
        List<String> nodes = new ArrayList<>();
        for (PlanPackage pk : p.packages()) if (!nodes.contains(pk.nodeId())) nodes.add(pk.nodeId());
        return new PlanSummary(p.id(), p.policy(), p.packageCount(), nodes, p.totalCost() == null ? null : p.totalCost().amount(), p.pnl() == null ? null : p.pnl().marginRatio(),
            p.packages().isEmpty() ? null : p.onTimeProbability(), p.latestEta() == null ? null : p.latestEta().toString(), p.meetsMarginFloor(), SIMULATED);
    }

    public boolean split() { return packages > 1; }

    public double cost() { return totalCost == null ? 0.0 : totalCost.doubleValue(); }

    /** Plan with the recorded policy, else the best-scoring one (options are returned best first). */
    public static SourcingPlan pick(List<SourcingPlan> options, String policy) {
        if (options == null || options.isEmpty()) return null;
        if (policy != null) for (SourcingPlan p : options) if (policy.equals(p.policy())) return p;
        return options.get(0);
    }
}
