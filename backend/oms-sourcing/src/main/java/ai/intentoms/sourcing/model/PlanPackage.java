package ai.intentoms.sourcing.model;

import ai.intentoms.finance.model.Money;

import java.time.LocalDate;
import java.util.List;

/**
 * One package (release) of a plan: everything shipped together from one node (FR-SRC-02).
 *
 * @param eta               promised delivery date
 * @param onTimeProbability rate-card on-time for the transit × node reliability × (1 − learned penalty)
 * @param landedCost        duty + brokerage + surcharges when the node's customs origin differs from the destination country
 * @param handlingCost      node handling (costProfile.handling × units)
 * @param markdownRisk      expected markdown loss (attributes.markdown_risk × line value)
 */
public record PlanPackage(String nodeId, List<Allocation> allocations, LocalDate eta, double onTimeProbability, Money shippingCost, Money labourCost,
                          Money packagingCost, Money landedCost, Money handlingCost, Money markdownRisk, int transitDays, double distanceKm) {
    public PlanPackage {
        allocations = allocations == null ? List.of() : List.copyOf(allocations);
    }

    public int units() { int n = 0; for (Allocation a : allocations) n += a.qty(); return n; }

    /** Shipping + labour + packaging + handling + landed cost + markdown risk. */
    public Money totalCost() { return shippingCost.add(labourCost).add(packagingCost).add(handlingCost).add(landedCost).add(markdownRisk); }
}
