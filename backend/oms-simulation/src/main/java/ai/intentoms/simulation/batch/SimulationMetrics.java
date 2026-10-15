package ai.intentoms.simulation.batch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure accumulator of the FR-SIM-02 report metrics for one arm (baseline or simulated) of a batch: cost (Σ plan total
 * cost), split rate (share of sourced orders with more than one package), on-time rate (mean plan on-time probability),
 * margin (mean plan margin ratio), touchless rate (share of orders whose decisions all act with no escalation) and
 * escalations (decisions needing a human). Rates over zero orders are reported as 0, never NaN.
 */
public final class SimulationMetrics {
    private int orders;
    private int sourcedOrders;
    private int unsourcedOrders;
    private double cost;
    private int splitOrders;
    private double onTimeSum;
    private int onTimeCount;
    private double marginSum;
    private int marginCount;
    private int touchlessOrders;
    private int escalations;
    private int decisions;
    private int packages;

    /** Add one order: {@code plan} may be null (no feasible plan); {@code touchless} / {@code escalated} describe its decisions. */
    public void add(ai.intentoms.simulation.replay.PlanSummary plan, int decisionCount, boolean touchless, int escalatedDecisions) {
        orders++;
        decisions += decisionCount;
        escalations += escalatedDecisions;
        if (touchless && escalatedDecisions == 0) touchlessOrders++;
        if (plan == null) { unsourcedOrders++; return; }
        sourcedOrders++;
        cost += plan.cost();
        packages += plan.packages();
        if (plan.split()) splitOrders++;
        if (plan.onTimeProbability() != null) { onTimeSum += plan.onTimeProbability(); onTimeCount++; }
        if (plan.marginRatio() != null) { marginSum += plan.marginRatio(); marginCount++; }
    }

    public int orders() { return orders; }

    public Snapshot snapshot() {
        return new Snapshot(orders, sourcedOrders, unsourcedOrders, round(cost), orders == 0 ? 0 : round(cost / orders), packages, rate(splitOrders, sourcedOrders),
            onTimeCount == 0 ? 0 : round(onTimeSum / onTimeCount), marginCount == 0 ? 0 : round(marginSum / marginCount), rate(touchlessOrders, orders), escalations, decisions);
    }

    /**
     * One arm's metrics.
     *
     * @param cost          Σ plan total cost in the tenant currency
     * @param costPerOrder  cost / orders
     * @param splitRate     split orders / sourced orders
     * @param onTimeRate    mean plan on-time probability
     * @param margin        mean plan contribution-margin ratio
     * @param touchlessRate orders with every decision acting and no escalation / orders
     */
    public record Snapshot(int orders, int sourcedOrders, int unsourcedOrders, double cost, double costPerOrder, int packages, double splitRate, double onTimeRate, double margin,
                           double touchlessRate, int escalations, int decisions) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("orders", orders);
            m.put("sourcedOrders", sourcedOrders);
            m.put("unsourcedOrders", unsourcedOrders);
            m.put("cost", cost);
            m.put("costPerOrder", costPerOrder);
            m.put("packages", packages);
            m.put("splitRate", splitRate);
            m.put("onTimeRate", onTimeRate);
            m.put("margin", margin);
            m.put("touchlessRate", touchlessRate);
            m.put("escalations", escalations);
            m.put("decisions", decisions);
            return m;
        }

        /** simulated − baseline, field by field. */
        public static Map<String, Object> delta(Snapshot baseline, Snapshot simulated) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("orders", simulated.orders - baseline.orders);
            m.put("cost", round(simulated.cost - baseline.cost));
            m.put("costPerOrder", round(simulated.costPerOrder - baseline.costPerOrder));
            m.put("packages", simulated.packages - baseline.packages);
            m.put("splitRate", round(simulated.splitRate - baseline.splitRate));
            m.put("onTimeRate", round(simulated.onTimeRate - baseline.onTimeRate));
            m.put("margin", round(simulated.margin - baseline.margin));
            m.put("touchlessRate", round(simulated.touchlessRate - baseline.touchlessRate));
            m.put("escalations", simulated.escalations - baseline.escalations);
            m.put("unsourcedOrders", simulated.unsourcedOrders - baseline.unsourcedOrders);
            return m;
        }
    }

    /**
     * The job's {@code results} document: {@code baseline}, {@code simulated}, {@code delta}, {@code perOrderSample} plus the
     * flat fields the UI reads ({@code orders, costPerOrder, baselineCostPerOrder, splitRate, baselineSplitRate, ...}).
     */
    public static Map<String, Object> report(Snapshot baseline, Snapshot simulated, List<Map<String, Object>> perOrderSample, int processed, int flips, int blockedTransitions, boolean partial) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("baseline", baseline.toMap());
        r.put("simulated", simulated.toMap());
        r.put("delta", Snapshot.delta(baseline, simulated));
        r.put("perOrderSample", perOrderSample == null ? List.of() : new ArrayList<>(perOrderSample));
        r.put("processed", processed);
        r.put("flippedDecisions", flips);
        r.put("blockedTransitions", blockedTransitions);
        r.put("partial", partial);
        r.put("orders", simulated.orders);
        r.put("costPerOrder", simulated.costPerOrder);
        r.put("baselineCostPerOrder", baseline.costPerOrder);
        r.put("splitRate", simulated.splitRate);
        r.put("baselineSplitRate", baseline.splitRate);
        r.put("onTimeRate", simulated.onTimeRate);
        r.put("baselineOnTimeRate", baseline.onTimeRate);
        r.put("marginRatio", simulated.margin);
        r.put("baselineMarginRatio", baseline.margin);
        r.put("touchlessRate", simulated.touchlessRate);
        r.put("baselineTouchlessRate", baseline.touchlessRate);
        r.put("escalations", simulated.escalations);
        r.put("baselineEscalations", baseline.escalations);
        return r;
    }

    static double rate(int part, int whole) { return whole == 0 ? 0.0 : round(part / (double) whole); }

    static double round(double d) { return Math.round(d * 1_000_000d) / 1_000_000d; }
}
