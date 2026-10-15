package ai.intentoms.sourcing.model;

import ai.intentoms.finance.model.Money;
import ai.intentoms.finance.pnl.Pnl;

import java.util.List;
import java.util.Map;

/**
 * A complete fulfilment proposal for an order under one policy (FR-SRC-02, FR-SRC-03).
 *
 * @param policy           ship_complete | ship_as_available | split_up_to_k | pickup_consolidated | earliest_partial
 * @param totalCost        sum of package costs plus the split penalty for every package beyond the first
 * @param pnl              order P&L over the allocated lines with this plan's costs (FR-FIN-04)
 * @param score            composite plan score (cost, time, Jev soft score, split), higher is better
 * @param meetsMarginFloor contribution margin ratio ≥ tenant {@code sourcing.margin_floor} (GR-01, FR-SRC-04)
 * @param unsourcedLineIds lines (or line remainders) with no feasible node under this plan
 * @param explain          why each node was picked, component scores, weights, soft scores
 */
public record SourcingPlan(String id, String policy, List<PlanPackage> packages, Money totalCost, Pnl pnl, double score, boolean meetsMarginFloor,
                           List<String> unsourcedLineIds, Map<String, Object> explain) {
    public SourcingPlan {
        packages = packages == null ? List.of() : List.copyOf(packages);
        unsourcedLineIds = unsourcedLineIds == null ? List.of() : List.copyOf(unsourcedLineIds);
        explain = explain == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(explain));
    }

    public int packageCount() { return packages.size(); }
    public boolean complete() { return unsourcedLineIds.isEmpty(); }

    /** Weakest-link on-time probability across packages (1.0 for an empty plan). */
    public double onTimeProbability() {
        double p = 1.0;
        for (PlanPackage pk : packages) p = Math.min(p, pk.onTimeProbability());
        return p;
    }

    /** Latest package ETA, or null for an empty plan. */
    public java.time.LocalDate latestEta() {
        java.time.LocalDate d = null;
        for (PlanPackage pk : packages) if (pk.eta() != null && (d == null || pk.eta().isAfter(d))) d = pk.eta();
        return d;
    }

    public SourcingPlan withScore(double s, Map<String, Object> extraExplain) {
        Map<String, Object> ex = new java.util.LinkedHashMap<>(explain);
        if (extraExplain != null) ex.putAll(extraExplain);
        return new SourcingPlan(id, policy, packages, totalCost, pnl, s, meetsMarginFloor, unsourcedLineIds, ex);
    }

    public SourcingPlan withExplain(String key, Object value) {
        Map<String, Object> ex = new java.util.LinkedHashMap<>(explain);
        ex.put(key, value);
        return new SourcingPlan(id, policy, packages, totalCost, pnl, score, meetsMarginFloor, unsourcedLineIds, ex);
    }
}
