package ai.intentoms.sourcing.plan;

import ai.intentoms.order.model.FulfilmentGroup;
import ai.intentoms.order.model.MarginSnapshot;
import ai.intentoms.order.model.Order;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.sourcing.CandidateGenerator;
import ai.intentoms.sourcing.NodeRegistry;
import ai.intentoms.sourcing.SourcingService;
import ai.intentoms.sourcing.SourcingTestBase;
import ai.intentoms.sourcing.model.Candidate;
import ai.intentoms.sourcing.model.Policies;
import ai.intentoms.sourcing.model.SimulationContext;
import ai.intentoms.sourcing.model.SimulationOverrides;
import ai.intentoms.sourcing.model.SourcingPlan;
import ai.intentoms.sourcing.model.SourcingRequest;
import ai.intentoms.sourcing.model.SourcingResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** End-to-end sourcing against the demo nodes, rate card and decision catalogue (FR-SRC-01..04, FR-SRC-07, GR-01, NFR-05). */
class SourcingServiceIntegrationTest extends SourcingTestBase {
    @Autowired SourcingService sourcing;
    @Autowired CandidateGenerator candidates;
    @Autowired NodeRegistry nodes;
    @Autowired SourcingPlanRepository planRepo;
    final Actor actor = Actor.user("tester");

    @Test
    void shipCompletePicksOneNodeAndPersistsGroupsMarginAndEvent() {
        stock("TEE-BLK-M", "DC-NJ", 50); stock("HOOD-GRY-M", "DC-NJ", 50);
        stock("TEE-BLK-M", "DC-TX", 50); stock("HOOD-GRY-M", "DC-TX", 50);
        Order o = order("marketplace", null, NEW_YORK, line("L1", "TEE-BLK-M", 2, 28, 7.5), line("L2", "HOOD-GRY-M", 1, 68, 21));

        SourcingResult r = sourcing.source(o.id(), actor);

        assertThat(r.status()).isEqualTo(SourcingResult.Status.SOURCED);
        assertThat(r.plan().policy()).isEqualTo(Policies.SHIP_COMPLETE);
        assertThat(r.plan().packages()).hasSize(1);
        assertThat(r.plan().packages().get(0).nodeId()).isEqualTo("DC-NJ");        // 60 km vs 2,200 km
        assertThat(r.plan().meetsMarginFloor()).isTrue();
        assertThat(r.decisionRecordId()).isNotBlank();
        assertThat(r.reasons()).anyMatch(s -> s.contains("channel 'marketplace' default ship_complete"));

        Order saved = orders.get(o.id());
        assertThat(saved.fulfilmentGroups()).hasSize(1);
        FulfilmentGroup g = saved.fulfilmentGroups().get(0);
        assertThat(g.id()).startsWith("fg_");
        assertThat(g.nodeId()).isEqualTo("DC-NJ");
        assertThat(g.status()).isEqualTo("planned");
        assertThat(g.allocations()).hasSize(2);
        assertThat(g.promiseDate()).isEqualTo(r.plan().packages().get(0).eta());
        assertThat(g.sourcingPlanId()).isEqualTo(r.plan().id());
        assertThat(g.decisionRecordId()).isEqualTo(r.decisionRecordId());
        assertThat(g.explain()).containsKeys("reason", "softScore", "planScore");
        assertThat(saved.lines()).allMatch(l -> g.id().equals(l.fulfilmentGroupId()));
        MarginSnapshot m = saved.margin();
        assertThat(m.belowFloor()).isFalse();
        assertThat(m.marginRatio()).isEqualTo(r.plan().pnl().marginRatio());
        assertThat(m.floor()).isEqualTo(0.12);

        var sourced = events("order", o.id(), DefaultSourcingService.EVT_SOURCED);
        assertThat(sourced).hasSize(1);
        assertThat(sourced.get(0)).containsKeys("planId", "policy", "packages", "totalCost", "margin", "meetsFloor", "decisionRecordId", "softScores");
        assertThat(sourced.get(0).get("policy")).isEqualTo(Policies.SHIP_COMPLETE);
        var persisted = planRepo.forOrder(o.id());
        assertThat(persisted).isNotEmpty();
        assertThat(persisted.stream().filter(SourcingPlanRepository.Row::chosen).count()).isEqualTo(1);
        assertThat(persisted.stream().map(SourcingPlanRepository.Row::policy).distinct()).contains(Policies.SHIP_COMPLETE, Policies.SHIP_AS_AVAILABLE);
    }

    @Test
    void shipAsAvailableSplitsWhenNoSingleNodeCoversTheOrder() {
        stock("TEE-BLK-M", "DC-NJ", 50);
        stock("HOOD-GRY-M", "DC-TX", 50);
        Order o = order("web", null, NEW_YORK, line("L1", "TEE-BLK-M", 1, 60, 10), line("L2", "HOOD-GRY-M", 1, 120, 30));

        List<SourcingPlan> options = sourcing.options(o.id());
        assertThat(options).extracting(SourcingPlan::policy).doesNotContain(Policies.SHIP_COMPLETE);
        assertThat(options).isSortedAccordingTo((a, b) -> Double.compare(b.score(), a.score()));

        SourcingResult r = sourcing.source(o.id(), actor);
        assertThat(r.status()).isEqualTo(SourcingResult.Status.SOURCED);
        assertThat(r.plan().policy()).isEqualTo(Policies.SHIP_AS_AVAILABLE);
        assertThat(r.plan().packages()).hasSize(2);
        assertThat(r.plan().packages()).extracting(p -> p.nodeId()).containsExactlyInAnyOrder("DC-NJ", "DC-TX");
        assertThat(r.plan().totalCost().amount()).isGreaterThan(bd("12.00"));            // split penalty included
        assertThat(r.reasons()).noneMatch(s -> s.startsWith("split_acceptability"));      // nothing to fall back to, so the T2 check is skipped
        assertThat(r.plan().explain()).containsKeys("policyReasons", "softScoring");
        Order saved = orders.get(o.id());
        assertThat(saved.fulfilmentGroups()).hasSize(2);
        assertThat(saved.line("L1").orElseThrow().fulfilmentGroupId()).isNotEqualTo(saved.line("L2").orElseThrow().fulfilmentGroupId());
    }

    @Test
    void splitAverseIntentPrefersShipCompleteEvenOnWebChannel() {
        stock("TEE-BLK-M", "DC-NJ", 50); stock("HOOD-GRY-M", "DC-NJ", 50);
        stock("TEE-BLK-M", "DC-TX", 50); stock("HOOD-GRY-M", "DC-TX", 50);
        Order o = order("web", intent(Policies.SHIP_AS_AVAILABLE, false), NEW_YORK, line("L1", "TEE-BLK-M", 1, 28, 7.5), line("L2", "HOOD-GRY-M", 1, 68, 21));
        SourcingResult r = sourcing.source(o.id(), actor);
        assertThat(r.status()).isEqualTo(SourcingResult.Status.SOURCED);
        assertThat(r.plan().policy()).isEqualTo(Policies.SHIP_COMPLETE);
        assertThat(r.reasons()).anyMatch(s -> s.contains("splits are not acceptable"));
    }

    @Test
    void marginFloorBreachRequiresApprovalOffersAlternativesAndPersistsNoGroups() {
        // Thin margins: a two-package earliest_partial plan from the Chicago store + NJ breaches the floor; ship_complete from NJ clears it.
        stock("TEE-BLK-M", "ST-CHI-01", 10);
        stock("TEE-BLK-M", "DC-NJ", 50); stock("HOOD-GRY-M", "DC-NJ", 50);
        Order o = order("web", intent(Policies.EARLIEST_PARTIAL, true), CHICAGO,
            line("L1", "HOOD-GRY-M", 1, 50, 30), line("L2", "TEE-BLK-M", 1, 50, 30, Map.of("urgent", true)));

        SourcingResult r = sourcing.source(o.id(), actor);

        assertThat(r.status()).isEqualTo(SourcingResult.Status.REQUIRES_APPROVAL);
        assertThat(r.plan().policy()).isEqualTo(Policies.EARLIEST_PARTIAL);
        assertThat(r.plan().packages()).hasSize(2);
        assertThat(r.plan().meetsMarginFloor()).isFalse();
        assertThat(r.alternatives()).isNotEmpty();
        assertThat(r.alternatives()).allMatch(SourcingPlan::meetsMarginFloor);
        assertThat(r.alternatives()).extracting(SourcingPlan::policy).contains(Policies.SHIP_COMPLETE);
        assertThat(r.reasons()).anyMatch(s -> s.contains("below the floor"));

        Order saved = orders.get(o.id());
        assertThat(saved.fulfilmentGroups()).isEmpty();
        assertThat(saved.lines()).allMatch(l -> l.fulfilmentGroupId() == null);
        assertThat(saved.margin().belowFloor()).isTrue();
        assertThat(saved.margin().approvalPresent()).isFalse();
        assertThat(events("order", o.id(), DefaultSourcingService.EVT_SOURCED)).isEmpty();
        var blocked = events("order", o.id(), DefaultSourcingService.EVT_BLOCKED);
        assertThat(blocked).hasSize(1);
        assertThat(blocked.get(0)).containsKeys("planId", "policy", "marginRatio", "floor", "alternatives", "decisionRecordId");
        assertThat((List<?>) blocked.get(0).get("alternatives")).isNotEmpty();
        assertThat(planRepo.forOrder(o.id())).isNotEmpty().allMatch(row -> !row.chosen());

        // An approval on the order (set by the L3 task) lets the same plan through.
        MarginSnapshot m = saved.margin();
        orders.update(saved.withMargin(new MarginSnapshot(m.revenue(), m.contributionMargin(), m.marginRatio(), m.floor(), m.belowFloor(), true, "task_1", m.computedAt())));
        SourcingResult approved = sourcing.source(o.id(), actor);
        assertThat(approved.status()).isEqualTo(SourcingResult.Status.SOURCED);
        assertThat(approved.plan().policy()).isEqualTo(Policies.EARLIEST_PARTIAL);
        assertThat(approved.reasons()).anyMatch(s -> s.contains("approved (task task_1)"));
        assertThat(approved.reasons()).anyMatch(s -> s.startsWith("split_acceptability"));  // T2 check ran on the 2-package plan; heuristic never acts
        assertThat(approved.plan().explain()).containsKey("splitAcceptability");
        Order afterApproval = orders.get(o.id());
        assertThat(afterApproval.fulfilmentGroups()).hasSize(2);
        assertThat(afterApproval.margin().approvalPresent()).isTrue();
        assertThat(afterApproval.margin().approvalTaskId()).isEqualTo("task_1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void resourceExcludesTheRejectingNodePenalisesItAndRePlans() {
        stock("TEE-BLK-M", "DC-NJ", 50); stock("HOOD-GRY-M", "DC-NJ", 50);
        stock("TEE-BLK-M", "DC-TX", 50); stock("HOOD-GRY-M", "DC-TX", 50);
        Order o = order("marketplace", null, NEW_YORK, line("L1", "TEE-BLK-M", 1, 28, 7.5), line("L2", "HOOD-GRY-M", 1, 68, 21));
        SourcingResult first = sourcing.source(o.id(), actor);
        assertThat(first.plan().packages().get(0).nodeId()).isEqualTo("DC-NJ");
        String firstGroup = orders.get(o.id()).fulfilmentGroups().get(0).id();

        SourcingResult r = sourcing.resource(o.id(), "short_pick", actor);

        assertThat(r.status()).isEqualTo(SourcingResult.Status.SOURCED);
        assertThat(r.plan().packages()).hasSize(1);
        assertThat(r.plan().packages().get(0).nodeId()).isEqualTo("DC-TX");
        assertThat(nodes.penalty("DC-NJ", "TEE-BLK-M")).isGreaterThanOrEqualTo(0.3);
        assertThat(nodes.penalty("DC-NJ", "HOOD-GRY-M")).isGreaterThanOrEqualTo(0.3);
        assertThat(nodes.penalty("DC-TX", "TEE-BLK-M")).isZero();
        assertThat(events("node", "DC-NJ", "node.penalised")).hasSize(2);

        Order saved = orders.get(o.id());
        assertThat(saved.fulfilmentGroups()).hasSize(2);
        FulfilmentGroup rejected = saved.fulfilmentGroups().stream().filter(g -> g.id().equals(firstGroup)).findFirst().orElseThrow();
        assertThat(rejected.status()).isEqualTo("rejected");
        assertThat(rejected.explain()).containsEntry("rejectedReason", "short_pick");
        FulfilmentGroup active = saved.fulfilmentGroups().stream().filter(g -> "planned".equals(g.status())).findFirst().orElseThrow();
        assertThat(active.nodeId()).isEqualTo("DC-TX");
        assertThat(saved.lines()).allMatch(l -> active.id().equals(l.fulfilmentGroupId()));
        var resourced = events("order", o.id(), DefaultSourcingService.EVT_RESOURCED);
        assertThat(resourced).hasSize(1);
        assertThat(resourced.get(0)).containsEntry("reason", "short_pick").containsEntry("status", "SOURCED");
        assertThat((List<Object>) resourced.get(0).get("rejectedNodes")).containsExactly("DC-NJ");
        assertThat(events("order", o.id(), DefaultSourcingService.EVT_SOURCED)).hasSize(2);
    }

    @Test
    void noStockAnywhereIsNoFeasibleNodeWithFailureEvent() {
        Order o = order("web", null, NEW_YORK, line("L1", "DRONE-MINI", 1, 499, 210));
        SourcingResult r = sourcing.source(o.id(), actor);
        assertThat(r.status()).isEqualTo(SourcingResult.Status.NO_FEASIBLE_NODE);
        assertThat(r.plan()).isNull();
        assertThat(events("order", o.id(), DefaultSourcingService.EVT_FAILED)).hasSize(1);
        assertThat(orders.get(o.id()).fulfilmentGroups()).isEmpty();
    }

    @Test
    void candidateGeneratorAppliesCapabilitiesDistanceAndSimulationOverrides() {
        stock("PERF-50ML", "DC-NJ", 10); stock("PERF-50ML", "DC-NV", 10); stock("PERF-50ML", "DC-TX", 10); stock("PERF-50ML", "ST-CHI-01", 10);
        SourcingRequest req = new SourcingRequest("ord_x", List.of(new SourcingRequest.RequestLine("L1", "PERF-50ML", 1, ai.intentoms.finance.model.Money.usd(120), null, Map.of("hazmat", true, "cogs", 22))),
            new SourcingRequest.Destination("60611", "IL", "US"), "ground", "standard_ship", SourcingRequest.Constraints.none(), null);

        List<Candidate> all = candidates.generate(req, 0);
        assertThat(all).extracting(Candidate::nodeId).containsExactly("DC-NJ", "DC-NV");   // hazmat excludes the store and DC-TX; complete first, then distance
        assertThat(all).allMatch(Candidate::complete);
        assertThat(all.get(0).distanceKm()).isLessThan(all.get(1).distanceKm());

        List<Candidate> limited = candidates.generate(req, 1);
        assertThat(limited).hasSize(1);

        List<Candidate> simulated = SimulationContext.with(new SimulationOverrides(Set.of("DC-NJ"), true, 0.0), () -> candidates.generate(req, 0));
        assertThat(simulated).extracting(Candidate::nodeId).containsExactly("DC-NV");
        assertThat(simulated.get(0).transitDays()).isEqualTo(all.get(1).transitDays() + SimulationOverrides.OUTAGE_EXTRA_DAYS);

        SourcingRequest near = req.withConstraints(new SourcingRequest.Constraints(Set.of(), Set.of(), 500.0, false, Set.of()));
        assertThat(candidates.generate(near, 0)).isEmpty();                                   // both DCs are > 500 km from Chicago
        SourcingRequest storesOnly = req.withConstraints(new SourcingRequest.Constraints(Set.of("store"), Set.of(), null, false, Set.of()));
        assertThat(candidates.generate(storesOnly, 0)).isEmpty();                             // the only stocked store lacks hazmat
    }

    @Test
    void simulationOverridesCloseNodesForSourcingToo() {
        stock("TEE-BLK-M", "DC-NJ", 50); stock("TEE-BLK-M", "DC-TX", 50);
        Order o = order("marketplace", null, NEW_YORK, line("L1", "TEE-BLK-M", 1, 28, 7.5));
        SourcingResult r = SimulationContext.with(new SimulationOverrides(Set.of("DC-NJ"), false, 0.0), () -> sourcing.source(o.id(), actor));
        assertThat(r.status()).isEqualTo(SourcingResult.Status.SOURCED);
        assertThat(r.plan().packages().get(0).nodeId()).isEqualTo("DC-TX");
        assertThat(r.plan().explain()).containsKey("simulation");
    }
}
