package ai.intentoms.simulation.batch;

import ai.intentoms.simulation.replay.Outcome;
import ai.intentoms.simulation.replay.PlanSummary;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One order's baseline vs simulated outcome inside a batch (FR-SIM-02).
 *
 * @param baselinePlan       the recorded plan (or a fresh plan without overrides when nothing was recorded); null = unsourced
 * @param simulatedPlan      the plan under the scenario; null = no feasible plan
 * @param decisions          per decision name: baseline and simulated outcome
 * @param blockedTransitions recorded triggers the proposed state model would have blocked (0 when none requested)
 */
public record OrderSimulation(String orderId, PlanSummary baselinePlan, PlanSummary simulatedPlan, List<DecisionPair> decisions, int blockedTransitions, String error) {
    public OrderSimulation { decisions = decisions == null ? List.of() : List.copyOf(decisions); }

    public record DecisionPair(String decision, int versionBefore, int versionAfter, Outcome baseline, Outcome simulated) {
        public boolean flipped() { return simulated.flippedFrom(baseline); }
    }

    public boolean baselineTouchless() { return decisions.stream().allMatch(d -> d.baseline().acts()); }
    public boolean simulatedTouchless() { return decisions.stream().allMatch(d -> d.simulated().acts()); }
    public int baselineEscalations() { return (int) decisions.stream().filter(d -> d.baseline().escalated()).count(); }
    public int simulatedEscalations() { return (int) decisions.stream().filter(d -> d.simulated().escalated()).count(); }
    public List<String> flippedDecisions() { return decisions.stream().filter(DecisionPair::flipped).map(DecisionPair::decision).toList(); }

    /** Compact row for {@code results.perOrderSample}. */
    public Map<String, Object> sampleRow() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderId", orderId);
        m.put("costBefore", baselinePlan == null ? null : baselinePlan.totalCost());
        m.put("costAfter", simulatedPlan == null ? null : simulatedPlan.totalCost());
        m.put("packagesBefore", baselinePlan == null ? null : baselinePlan.packages());
        m.put("packagesAfter", simulatedPlan == null ? null : simulatedPlan.packages());
        m.put("nodesBefore", baselinePlan == null ? List.of() : baselinePlan.nodeIds());
        m.put("nodesAfter", simulatedPlan == null ? List.of() : simulatedPlan.nodeIds());
        m.put("onTimeBefore", baselinePlan == null ? null : baselinePlan.onTimeProbability());
        m.put("onTimeAfter", simulatedPlan == null ? null : simulatedPlan.onTimeProbability());
        m.put("marginBefore", baselinePlan == null ? null : baselinePlan.marginRatio());
        m.put("marginAfter", simulatedPlan == null ? null : simulatedPlan.marginRatio());
        m.put("flippedDecisions", flippedDecisions());
        m.put("blockedTransitions", blockedTransitions);
        if (error != null) m.put("error", error);
        return m;
    }
}
