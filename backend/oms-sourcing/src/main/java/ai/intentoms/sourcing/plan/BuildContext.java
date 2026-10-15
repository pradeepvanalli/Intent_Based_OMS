package ai.intentoms.sourcing.plan;

import ai.intentoms.sourcing.config.SourcingSettings;
import ai.intentoms.sourcing.model.Candidate;
import ai.intentoms.sourcing.model.SourcingRequest;
import ai.intentoms.sourcing.rates.RateCard;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Everything one plan-building run needs, resolved up front so the builder is pure and unit-testable.
 *
 * @param softScores       node id -> Jev composite soft score in [0,1] ({@code sourcing_node_score}); missing = neutral 0.5
 * @param now              the run's instant (cutoff checks)
 * @param today            the run's date (ETA base)
 * @param maxSplit         K, tenant {@code sourcing.max_split_packages}
 * @param splitPenaltyUsd  per extra package
 * @param marginFloor      tenant floor ratio (GR-01)
 * @param pickupRadiusKm   radius within which a BOPIS store is offered as pickup for a ship-to-home order
 * @param penalties        learned node+sku penalties (FR-SRC-07)
 */
public record BuildContext(SourcingRequest req, List<Candidate> candidates, RateCard card, Map<String, Double> softScores, Instant now, LocalDate today, int maxSplit,
                           boolean businessDaysOnly, double splitPenaltyUsd, double marginFloor, double pickupRadiusKm, SourcingSettings.Weights weights, PenaltyLookup penalties) {
    public BuildContext {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        softScores = softScores == null ? Map.of() : Map.copyOf(softScores);
        weights = weights == null ? SourcingSettings.Weights.defaults() : weights;
        penalties = penalties == null ? PenaltyLookup.nodeLevel() : penalties;
        maxSplit = Math.max(1, maxSplit);
    }

    public BuildContext withRequest(SourcingRequest r) {
        return new BuildContext(r, candidates, card, softScores, now, today, maxSplit, businessDaysOnly, splitPenaltyUsd, marginFloor, pickupRadiusKm, weights, penalties);
    }

    public double soft(String nodeId) { return softScores.getOrDefault(nodeId, PlanScorer.NEUTRAL_SOFT); }
}
