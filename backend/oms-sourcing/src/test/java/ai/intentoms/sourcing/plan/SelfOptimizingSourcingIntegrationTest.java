package ai.intentoms.sourcing.plan;

import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.learning.governance.ParameterResolver;
import ai.intentoms.learning.learner.FulfilmentFacts;
import ai.intentoms.learning.learner.LaneDeliveryLearner;
import ai.intentoms.learning.learner.NodeReliabilityLearner;
import ai.intentoms.learning.learner.PromiseBufferLearner;
import ai.intentoms.learning.learner.SourcingPolicyBandit;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.store.LearnedFactCache;
import ai.intentoms.order.model.Order;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.sourcing.NodeRegistry;
import ai.intentoms.sourcing.PromiseService;
import ai.intentoms.sourcing.SourcingService;
import ai.intentoms.sourcing.SourcingTestBase;
import ai.intentoms.sourcing.model.Promise;
import ai.intentoms.sourcing.model.PromiseRequest;
import ai.intentoms.sourcing.model.SourcingResult;
import ai.intentoms.sourcing.promise.DefaultPromiseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Learned values on the live sourcing and promise paths (FS-01, FS-02, FS-03, FS-06, CX-01, CX-02, IU-03): each one
 * changes behaviour only after its parameter family is promoted for live use, and the order events carry what the
 * learners need.
 */
class SelfOptimizingSourcingIntegrationTest extends SourcingTestBase {
    @Autowired SourcingService sourcing;
    @Autowired PromiseService promise;
    @Autowired DefaultPromiseService promiseImpl;
    @Autowired NodeRegistry nodes;
    @Autowired FulfilmentFacts facts;
    @Autowired LearnedFactCache factCache;
    @Autowired ParameterResolver parameters;
    @Autowired ai.intentoms.learning.experiment.ExperimentManager experiments;
    @Autowired DecisionRecordRepository decisionRecords;
    final Actor actor = Actor.user("tester");

    @Test
    void sourcedEventCarriesLaneClassAndBacklogAndTheBanditOnlyOffersSafeArms() {
        stock("TEE-BLK-M", "DC-NJ", 50);
        Order o = order("web", null, NEW_YORK, line("L1", "TEE-BLK-M", 2, 28, 7.5, Map.of("category", "Apparel")));
        SourcingResult r = sourcing.source(o.id(), actor);
        assertThat(r.status()).isEqualTo(SourcingResult.Status.SOURCED);

        Map<String, Object> sourced = events("order", o.id(), DefaultSourcingService.EVT_SOURCED).get(0);
        assertThat(sourced).containsEntry("destinationZip3", "100").containsEntry("shipMethod", "ground");
        @SuppressWarnings("unchecked") Map<String, Object> pkg = ((List<Map<String, Object>>) sourced.get("packages")).get(0);
        assertThat(pkg).containsEntry("skuClass", "apparel").containsKey("backlogUnits");

        DecisionRecord bandit = decisionRecords.forSubject("order", o.id()).stream().filter(d -> SourcingPolicyBandit.DECISION_NAME.equals(d.decision())).findFirst().orElseThrow();
        assertThat(bandit.details()).containsEntry("acted", false).containsKey("bucket");
        assertThat(bandit.propensity()).isEqualTo(1.0);
        // The offered arms are exactly the policies that clear the margin floor and deliver no later than the default's plan.
        List<String> safe = DefaultSourcingService.banditArms(sourcing.options(o.id()), "ship_as_available", 0);
        assertThat(bandit.arms()).containsExactlyInAnyOrderElementsOf(safe);
        assertThat(bandit.chosenArm()).isEqualTo("ship_as_available");
    }

    @Test
    void learnedRejectPenaltyAppliesOnlyWhenPromoted() {
        stock("TEE-BLK-M", "DC-NJ", 50); stock("TEE-BLK-M", "DC-TX", 50);
        stock("HOOD-GRY-M", "DC-NJ", 50); stock("HOOD-GRY-M", "DC-TX", 50);
        facts.number(SubjectType.NODE, "DC-NJ", FactKey.NODE_REJECT_PENALTY, 0.55, 40, NodeReliabilityLearner.KEY, 0.001);
        factCache.invalidateAll();

        Order first = order("marketplace", null, NEW_YORK, line("L1", "TEE-BLK-M", 1, 28, 7.5));
        sourcing.source(first.id(), actor);
        sourcing.resource(first.id(), "short_pick", actor);
        assertThat(((Number) events("order", first.id(), DefaultSourcingService.EVT_RESOURCED).get(0).get("penaltyDelta")).doubleValue()).isCloseTo(0.3, within(1e-9));

        parameters.promote(NodeReliabilityLearner.REJECT_PENALTY_PARAMETER, NodeReliabilityLearner.KEY, 0.05, 0.9, null, null, "test");
        Order second = order("marketplace", null, NEW_YORK, line("L1", "HOOD-GRY-M", 1, 68, 21));   // a SKU DC-NJ was not penalised for
        assertThat(sourcing.source(second.id(), actor).plan().packages().get(0).nodeId()).isEqualTo("DC-NJ");
        SourcingResult r = sourcing.resource(second.id(), "short_pick", actor);
        Map<String, Object> resourced = events("order", second.id(), DefaultSourcingService.EVT_RESOURCED).get(0);
        assertThat(((Number) resourced.get("penaltyDelta")).doubleValue()).isCloseTo(0.55, within(1e-9));
        assertThat(r.status()).isEqualTo(SourcingResult.Status.SOURCED);
    }

    @Test
    void agedStockBreaksNearTiesOnlyWhenPromoted() {
        stock("TEE-BLK-M", "DC-TX", 50); stock("TEE-BLK-M", "ST-AUS-03", 50);
        config.set(DefaultSourcingService.AGED_STOCK_COST_PCT, 5.0);   // treat every same-policy plan as "about the same cost" here
        facts.number(SubjectType.SKU_NODE, "TEE-BLK-M|ST-AUS-03", FactKey.STOCK_AGE_DAYS, 200, 10, "stock_age", 0.5);
        factCache.invalidateAll();

        Order before = order("marketplace", null, DALLAS, line("L1", "TEE-BLK-M", 1, 28, 7.5));
        SourcingResult plain = sourcing.source(before.id(), actor);
        assertThat(plain.plan().packages().get(0).nodeId()).isEqualTo("DC-TX");

        parameters.promote(DefaultSourcingService.AGED_STOCK_PARAMETER, "stock_age", null, null, null, null, "test");
        Order after = order("marketplace", null, DALLAS, line("L1", "TEE-BLK-M", 1, 28, 7.5));
        SourcingResult aged = sourcing.source(after.id(), actor);
        assertThat(aged.plan().packages().get(0).nodeId()).isEqualTo("ST-AUS-03");
        assertThat(aged.reasons()).anyMatch(s -> s.startsWith("aged-stock tie-break"));
        assertThat(aged.plan().explain()).containsKey("agedStockTieBreak");
    }

    @Autowired ai.intentoms.learning.service.ObjectiveService objectiveService;
    @Autowired DefaultSourcingService sourcingImpl;

    private void liveObjective(double cost, double inv) {
        var draft = objectiveService.draft(new ai.intentoms.learning.model.ObjectiveWeights(1, 1, cost, 1, 1, 1, inv), Map.of(), "user:strategy", null, "manual", Map.of());
        objectiveService.approve(draft.version(), "user:approver");
    }

    /**
     * IU-03: the cost the tie-break will pay to ship older stock is the reward's own trade-off: the base tolerance
     * ({@code sourcing.aged_stock_tiebreak_cost_pct}) times the live objective's aged-stock weight over its cost weight.
     * A plan a few percent dearer is taken only once the objective values aged-stock relief enough, and never at weight 0.
     */
    @Test
    void agedStockToleranceScalesWithTheObjectivesAgedStockWeight() {
        stock("TEE-BLK-M", "DC-TX", 50); stock("TEE-BLK-M", "ST-AUS-03", 50);
        facts.number(SubjectType.SKU_NODE, "TEE-BLK-M|ST-AUS-03", FactKey.STOCK_AGE_DAYS, 200, 10, "stock_age", 0.5);
        factCache.invalidateAll();
        parameters.promote(DefaultSourcingService.AGED_STOCK_PARAMETER, "stock_age", null, null, null, null, "test");

        // How much dearer the aged-stock plan is: the cheapest plan without the tie-break, the aged one with any tolerance.
        config.set(DefaultSourcingService.AGED_STOCK_COST_PCT, 0.0);
        double cheapest = sourcing.source(order("marketplace", null, DALLAS, line("L1", "TEE-BLK-M", 1, 28, 7.5)).id(), actor).plan().totalCost().amount().doubleValue();
        config.set(DefaultSourcingService.AGED_STOCK_COST_PCT, 5.0);
        SourcingResult anyCost = sourcing.source(order("marketplace", null, DALLAS, line("L1", "TEE-BLK-M", 1, 28, 7.5)).id(), actor);
        assertThat(anyCost.plan().packages().get(0).nodeId()).isEqualTo("ST-AUS-03");
        double gap = anyCost.plan().totalCost().amount().doubleValue() / cheapest - 1;
        assertThat(gap).as("the aged-stock plan costs more").isPositive();

        // Base tolerance two thirds of the gap: a balanced objective (aged stock weighs as much as cost) keeps the cheaper plan.
        double base = gap / 1.5;
        config.set(DefaultSourcingService.AGED_STOCK_COST_PCT, base);
        assertThat(sourcingImpl.agedStockTolerance()).isCloseTo(base, within(1e-9));
        assertThat(sourcing.source(order("marketplace", null, DALLAS, line("L1", "TEE-BLK-M", 1, 28, 7.5)).id(), actor).plan().packages().get(0).nodeId()).isEqualTo("DC-TX");

        // Aged stock weighted twice cost: the tolerance doubles past the gap and the older stock ships.
        liveObjective(1.0, 2.0);
        assertThat(sourcingImpl.agedStockTolerance()).isCloseTo(2 * base, within(1e-9));
        SourcingResult aged = sourcing.source(order("marketplace", null, DALLAS, line("L1", "TEE-BLK-M", 1, 28, 7.5)).id(), actor);
        assertThat(aged.plan().packages().get(0).nodeId()).isEqualTo("ST-AUS-03");
        Map<?, ?> tieBreak = (Map<?, ?>) aged.plan().explain().get("agedStockTieBreak");
        assertThat(((Number) tieBreak.get("costTolerance")).doubleValue()).isCloseTo(2 * base, within(1e-9));

        // Aged stock not valued at all: no tolerance, whatever the base.
        liveObjective(1.0, 0.0);
        config.set(DefaultSourcingService.AGED_STOCK_COST_PCT, 5.0);
        assertThat(sourcingImpl.agedStockTolerance()).isZero();
        assertThat(sourcing.source(order("marketplace", null, DALLAS, line("L1", "TEE-BLK-M", 1, 28, 7.5)).id(), actor).plan().packages().get(0).nodeId()).isEqualTo("DC-TX");
    }

    @Test
    void promiseUsesLearnedLaneTransitCalibrationAndBufferOnlyWhenPromoted() {
        stock("TEE-BLK-M", "DC-NJ", 50);
        config.set("sourcing.service_carriers", Map.of("ground", "UPS"));
        String lane = LaneDeliveryLearner.laneKey("UPS", "ground", "DC-NJ", "100");
        facts.number(SubjectType.LANE, lane, FactKey.LANE_TRANSIT_DAYS_P50, 3.0, 40, LaneDeliveryLearner.KEY, 0.01);
        facts.number(SubjectType.LANE, lane, FactKey.LANE_CALIBRATION_OFFSET, -0.2, 40, LaneDeliveryLearner.KEY, 0.001);
        facts.number(SubjectType.SEGMENT, PromiseBufferLearner.segment("web"), FactKey.PROMISE_BUFFER_DAYS, 2, 100, PromiseBufferLearner.KEY, 0.5);
        factCache.invalidateAll();
        PromiseRequest req = new PromiseRequest(List.of(new PromiseRequest.PromiseLine("TEE-BLK-M", 1)), "10001", "NY", "web", "ground");

        Promise staticPromise = promise.promise(req);
        assertThat(staticPromise.date()).isEqualTo(LocalDate.of(2026, 10, 7));        // rate card: zone 1, one day

        parameters.promote(LaneDeliveryLearner.TRANSIT_PARAMETER, LaneDeliveryLearner.KEY, 0.0, 10.0, null, null, "test");
        parameters.promote(LaneDeliveryLearner.CALIBRATION_PARAMETER, LaneDeliveryLearner.KEY, -0.3, 0.3, null, null, "test");
        promiseImpl.invalidateAll();
        Promise learned = promise.promise(req);
        assertThat(learned.date()).isEqualTo(LocalDate.of(2026, 10, 9));                // learned P50 of three days
        assertThat(learned.onTimeProbability()).isLessThan(staticPromise.onTimeProbability() - 0.15);

        parameters.promote(PromiseBufferLearner.PARAMETER, PromiseBufferLearner.KEY, 0.0, 5.0, null, null, "test");
        promiseImpl.invalidateAll();
        assertThat(promise.promise(req).date()).isEqualTo(LocalDate.of(2026, 10, 11));  // plus the web segment's two-day buffer
    }

    /** EX-04: a customer in the persistent holdout keeps the static reject penalty; a treated customer gets the learned one. */
    @Test
    void holdoutCustomersKeepTheStaticRejectPenaltyWhileOthersGetTheLearnedOne() {
        var holdout = experiments.ensureHoldout(0.2, "test");
        try {
            String held = null, treated = null;
            for (int i = 0; i < 1000 && (held == null || treated == null); i++) {
                String ref = "cust-holdout-" + i;
                if (parameters.inHoldout("customer:" + ref)) { if (held == null) held = ref; } else if (treated == null) treated = ref;
            }
            assertThat(held).as("a customer in the 20% holdout").isNotNull();
            stock("TEE-BLK-M", "DC-NJ", 50); stock("TEE-BLK-M", "DC-TX", 50);
            stock("HOOD-GRY-M", "DC-NJ", 50); stock("HOOD-GRY-M", "DC-TX", 50);
            facts.number(SubjectType.NODE, "DC-NJ", FactKey.NODE_REJECT_PENALTY, 0.55, 40, NodeReliabilityLearner.KEY, 0.001);
            factCache.invalidateAll();
            parameters.promote(NodeReliabilityLearner.REJECT_PENALTY_PARAMETER, NodeReliabilityLearner.KEY, 0.05, 0.9, null, null, "test");

            Order heldOrder = orders.insert(withCustomer(order("marketplace", null, NEW_YORK, line("L1", "TEE-BLK-M", 1, 28, 7.5)), held));
            sourcing.source(heldOrder.id(), actor);
            sourcing.resource(heldOrder.id(), "short_pick", actor);
            assertThat(((Number) events("order", heldOrder.id(), DefaultSourcingService.EVT_RESOURCED).get(0).get("penaltyDelta")).doubleValue())
                .as("holdout customer: static penalty").isCloseTo(0.3, within(1e-9));

            Order treatedOrder = orders.insert(withCustomer(order("marketplace", null, NEW_YORK, line("L1", "HOOD-GRY-M", 1, 68, 21)), treated));
            sourcing.source(treatedOrder.id(), actor);
            sourcing.resource(treatedOrder.id(), "short_pick", actor);
            assertThat(((Number) events("order", treatedOrder.id(), DefaultSourcingService.EVT_RESOURCED).get(0).get("penaltyDelta")).doubleValue())
                .as("treated customer: learned penalty").isCloseTo(0.55, within(1e-9));
        } finally {
            experiments.stop(holdout.experimentId(), ai.intentoms.learning.model.ExperimentStatus.STOPPED, java.util.Map.of(), ai.intentoms.platform.ledger.Actor.system("test"));
            parameters.revoke(NodeReliabilityLearner.REJECT_PENALTY_PARAMETER, "test");
        }
    }

    /** The same order for another customer (a fresh id, so the original insert is left untouched). */
    private static Order withCustomer(Order o, String customerRef) {
        return new Order(ai.intentoms.platform.ids.Ids.prefixed("ord"), o.channel(), ai.intentoms.platform.ids.Ids.shortId(), o.orderType(), customerRef, o.customer(), o.lines(),
            o.addresses(), o.charges(), o.tenders(), o.notes(), o.fulfilmentGroups(), o.intent(), o.provenance(), o.totals(), o.margin(), o.stateModelId(), o.stateModelVersion(),
            o.status(), o.shipMethod(), o.currency(), o.extensions(), o.placedAt(), o.capturedAt(), o.updatedAt(), o.version());
    }
}
