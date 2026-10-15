package ai.intentoms.sourcing.plan;

import ai.intentoms.finance.model.Money;
import ai.intentoms.sourcing.Fixtures;
import ai.intentoms.sourcing.config.SourcingSettings;
import ai.intentoms.sourcing.model.Candidate;
import ai.intentoms.sourcing.model.Node;
import ai.intentoms.sourcing.model.PlanPackage;
import ai.intentoms.sourcing.model.Policies;
import ai.intentoms.sourcing.model.SourcingPlan;
import ai.intentoms.sourcing.model.SourcingRequest;
import ai.intentoms.sourcing.rates.RateCard;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Plan building per policy with fake inventory (FR-SRC-03), scoring weights (FR-SRC-02) and the margin-floor flag (GR-01). */
class PlanBuilderTest {
    final RateCard card = Fixtures.demoCard();
    final PlanBuilder builder = new PlanBuilder(Fixtures.economics());
    final Node nj = Fixtures.dc("DC-NJ", 40.5187, -74.4121, "NJ", 1.10);
    final Node tx = Fixtures.dc("DC-TX", 32.7767, -96.7970, "TX", 1.05);
    final Node nv = Fixtures.dc("DC-NV", 39.5296, -119.8138, "NV", 1.25);
    final Node chi = Fixtures.store("ST-CHI-01", 41.8919, -87.6244, "IL");

    SourcingRequest twoLines(String zip, String state) {
        return Fixtures.request("ground", zip, state, List.of(Fixtures.line("L1", "A", 1, 100, 30), Fixtures.line("L2", "B", 2, 60, 20)));
    }

    List<Candidate> cands(SourcingRequest req, Map<Node, Map<String, Integer>> stock) {
        return stock.entrySet().stream().map(e -> Fixtures.candidate(e.getKey(), req, e.getValue(), card)).toList();
    }

    static SourcingPlan only(List<SourcingPlan> plans, String policy) {
        List<SourcingPlan> m = plans.stream().filter(p -> p.policy().equals(policy)).toList();
        return m.isEmpty() ? null : m.get(0);
    }

    @Test
    void shipCompleteNeedsOneNodeCoveringEverythingAndRanksVariantsByPreference() {
        SourcingRequest req = twoLines("10001", "NY");
        var plans = builder.build(Fixtures.context(req, cands(req, Map.of(nj, Map.of("A", 5, "B", 5), nv, Map.of("A", 5, "B", 5), tx, Map.of("A", 5))), Map.of(), SourcingSettings.Weights.defaults(), 0.12, 3));
        List<SourcingPlan> complete = plans.stream().filter(p -> p.policy().equals(Policies.SHIP_COMPLETE)).toList();
        assertThat(complete).hasSize(2);
        assertThat(complete.get(0).packages()).hasSize(1);
        assertThat(complete.get(0).packages().get(0).nodeId()).isEqualTo("DC-NJ");   // nearest and cheapest to New York
        assertThat(complete.get(0).packages().get(0).allocations()).extracting("lineId").containsExactly("L1", "L2");
        assertThat(complete.get(0).unsourcedLineIds()).isEmpty();
        assertThat(complete.get(0).explain()).containsKeys("nodes", "components", "weights", "marginRatio");
        assertThat(complete.get(0).score()).isGreaterThan(complete.get(1).score());
        // Nobody covers everything → no ship_complete plan at all.
        var none = builder.build(Fixtures.context(req, cands(req, Map.of(nj, Map.of("A", 5), tx, Map.of("B", 5))), Map.of(), SourcingSettings.Weights.defaults(), 0.12, 3));
        assertThat(only(none, Policies.SHIP_COMPLETE)).isNull();
    }

    @Test
    void shipAsAvailableIsGreedyAndLeavesRemainderUnsourced() {
        SourcingRequest req = twoLines("10001", "NY");
        // NJ has only A; TX has one of two B; nobody has the second B.
        var plans = builder.build(Fixtures.context(req, cands(req, Map.of(nj, Map.of("A", 5), tx, Map.of("B", 1))), Map.of(), SourcingSettings.Weights.defaults(), 0.12, 3));
        SourcingPlan p = only(plans, Policies.SHIP_AS_AVAILABLE);
        assertThat(p).isNotNull();
        assertThat(p.packages()).hasSize(2);
        assertThat(p.packages()).extracting(PlanPackage::nodeId).containsExactlyInAnyOrder("DC-NJ", "DC-TX");
        assertThat(p.unsourcedLineIds()).containsExactly("L2");           // one unit of L2 still missing
        assertThat(p.explain()).containsEntry("splitPenalty", Money.usd("12.00").amount());
        assertThat(only(plans, Policies.SPLIT_UP_TO_K)).isNull();          // incomplete → not a split_up_to_k plan
        assertThat(only(plans, Policies.SHIP_COMPLETE)).isNull();
    }

    @Test
    void greedyPrefersTheNodeCoveringMostUnitsThenRespectsK() {
        SourcingRequest req = Fixtures.request("ground", "10001", "NY", List.of(Fixtures.line("L1", "A", 1, 100, 30), Fixtures.line("L2", "B", 1, 100, 30), Fixtures.line("L3", "C", 1, 100, 30)));
        var cands = cands(req, Map.of(nj, Map.of("A", 1), tx, Map.of("B", 1, "C", 1), nv, Map.of("C", 1)));
        var plans = builder.build(Fixtures.context(req, cands, Map.of(), SourcingSettings.Weights.defaults(), 0.12, 3));
        SourcingPlan split = only(plans, Policies.SPLIT_UP_TO_K);
        assertThat(split).isNotNull();
        assertThat(split.packages()).hasSize(2);
        assertThat(split.packages().get(0).nodeId()).isEqualTo("DC-TX");   // 2 units beats 1
        assertThat(split.complete()).isTrue();
        // K = 1 → only one package; remainder unsourced, no split_up_to_k.
        var k1 = builder.build(Fixtures.context(req, cands, Map.of(), SourcingSettings.Weights.defaults(), 0.12, 1));
        assertThat(only(k1, Policies.SHIP_AS_AVAILABLE).packages()).hasSize(1);
        assertThat(only(k1, Policies.SHIP_AS_AVAILABLE).unsourcedLineIds()).containsExactly("L1");
        assertThat(only(k1, Policies.SPLIT_UP_TO_K)).isNull();
    }

    @Test
    void pickupConsolidatedUsesABopisStoreAndIsFreeToShip() {
        SourcingRequest req = twoLines("60611", "IL");
        var plans = builder.build(Fixtures.context(req, cands(req, Map.of(chi, Map.of("A", 5, "B", 5), nj, Map.of("A", 5, "B", 5))), Map.of(), SourcingSettings.Weights.defaults(), 0.12, 3));
        SourcingPlan pickup = only(plans, Policies.PICKUP_CONSOLIDATED);
        assertThat(pickup).isNotNull();
        assertThat(pickup.packages()).hasSize(1);
        assertThat(pickup.packages().get(0).nodeId()).isEqualTo("ST-CHI-01");
        assertThat(pickup.packages().get(0).shippingCost().isZero()).isTrue();
        assertThat(pickup.packages().get(0).eta()).isEqualTo(LocalDate.of(2026, 10, 6)); // before the 14:00 Chicago cutoff → today
        // A store 1,100 km away is not a pickup option for a ship-to-home order.
        SourcingRequest ny = twoLines("10001", "NY");
        var far = builder.build(Fixtures.context(ny, cands(ny, Map.of(chi, Map.of("A", 5, "B", 5), nj, Map.of("A", 5, "B", 5))), Map.of(), SourcingSettings.Weights.defaults(), 0.12, 3));
        assertThat(only(far, Policies.PICKUP_CONSOLIDATED)).isNull();
    }

    @Test
    void earliestPartialShipsTheUrgentLineFromTheFastestNodeThenTheRest() {
        SourcingRequest req = Fixtures.request("ground", "60611", "IL", List.of(Fixtures.line("L1", "A", 1, 100, 30), Fixtures.line("L2", "B", 1, 100, 30, Map.of("urgent", true))));
        var plans = builder.build(Fixtures.context(req, cands(req, Map.of(chi, Map.of("B", 5), nj, Map.of("A", 5, "B", 5))), Map.of(), SourcingSettings.Weights.defaults(), 0.12, 3));
        SourcingPlan p = only(plans, Policies.EARLIEST_PARTIAL);
        assertThat(p).isNotNull();
        assertThat(p.packages()).hasSize(2);
        assertThat(p.packages().get(0).nodeId()).isEqualTo("ST-CHI-01");
        assertThat(p.packages().get(0).allocations()).extracting("lineId").containsExactly("L2");
        assertThat(p.packages().get(0).transitDays()).isLessThan(p.packages().get(1).transitDays());
        assertThat(p.packages().get(1).nodeId()).isEqualTo("DC-NJ");
        assertThat(p.explain()).containsEntry("urgentLineId", "L2");
        assertThat(p.complete()).isTrue();
    }

    @Test
    void weightsSteerTheScoreAndNeutralSoftScoreIsHalf() {
        SourcingRequest req = twoLines("10001", "NY");
        var cands = cands(req, Map.of(nj, Map.of("A", 5, "B", 5), nv, Map.of("A", 5, "B", 5)));
        // Cost-only weights: cheapest (nearest) plan scores 1.0 on the cost component.
        var costOnly = builder.build(Fixtures.context(req, cands, Map.of(), new SourcingSettings.Weights(1, 0, 0, 0), 0.12, 3));
        assertThat(costOnly.get(0).packages().get(0).nodeId()).isEqualTo("DC-NJ");
        assertThat(costOnly.get(0).score()).isEqualTo(1.0);
        @SuppressWarnings("unchecked") Map<String, Object> comps = (Map<String, Object>) costOnly.get(0).explain().get("components");
        assertThat(comps).containsEntry("soft", 0.5);
        // Soft-only weights with Jev preferring Nevada flips the order.
        var softOnly = builder.build(Fixtures.context(req, cands, Map.of("DC-NV", 0.95, "DC-NJ", 0.2), new SourcingSettings.Weights(0, 0, 1, 0), 0.12, 3));
        assertThat(softOnly.get(0).packages().get(0).nodeId()).isEqualTo("DC-NV");
        assertThat(softOnly.get(0).score()).isCloseTo(0.95, within(1e-6));
        // Split weight penalises extra packages: (1 − 1/3) for two packages with K = 3.
        var splitOnly = builder.build(Fixtures.context(req, cands(req, Map.of(nj, Map.of("A", 5), tx, Map.of("B", 5))), Map.of(), new SourcingSettings.Weights(0, 0, 0, 1), 0.12, 3));
        assertThat(only(splitOnly, Policies.SHIP_AS_AVAILABLE).score()).isCloseTo(1 - 1 / 3.0, within(1e-4));
    }

    @Test
    void marginFloorFlagFollowsThePnl() {
        SourcingRequest rich = twoLines("10001", "NY");
        var ok = builder.build(Fixtures.context(rich, cands(rich, Map.of(nj, Map.of("A", 5, "B", 5))), Map.of(), SourcingSettings.Weights.defaults(), 0.12, 3));
        assertThat(ok.get(0).meetsMarginFloor()).isTrue();
        assertThat(ok.get(0).pnl().marginRatio()).isGreaterThan(0.12);
        SourcingRequest thin = Fixtures.request("ground", "10001", "NY", List.of(Fixtures.line("L1", "A", 1, 20, 17)));
        var breach = builder.build(Fixtures.context(thin, cands(thin, Map.of(nj, Map.of("A", 5))), Map.of(), SourcingSettings.Weights.defaults(), 0.12, 3));
        assertThat(breach.get(0).meetsMarginFloor()).isFalse();
        assertThat(breach.get(0).explain()).containsEntry("meetsMarginFloor", false);
        // The same plan clears a zero floor when the margin is merely negative? No: negative never clears 0.
        var zeroFloor = builder.build(Fixtures.context(thin, cands(thin, Map.of(nj, Map.of("A", 5))), Map.of(), SourcingSettings.Weights.defaults(), 0.0, 3));
        assertThat(zeroFloor.get(0).meetsMarginFloor()).isEqualTo(zeroFloor.get(0).pnl().marginRatio() >= 0);
    }

    @Test
    void etaUsesTransitDaysAndOnTimeCombinesCardReliabilityAndPenalty() {
        SourcingRequest req = twoLines("10001", "NY");
        Node penalised = nj.withPenalty(0.5);
        var plans = builder.build(Fixtures.context(req, List.of(Fixtures.candidate(penalised, req, Map.of("A", 5, "B", 5), card)), Map.of(), SourcingSettings.Weights.defaults(), 0.12, 3));
        PlanPackage pk = plans.get(0).packages().get(0);
        assertThat(pk.transitDays()).isEqualTo(1);
        assertThat(pk.eta()).isEqualTo(LocalDate.of(2026, 10, 7));
        assertThat(pk.onTimeProbability()).isCloseTo(0.95 * 0.99 * 0.5, within(1e-3));
    }
}
