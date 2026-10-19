package ai.intentoms.sourcing.plan;

import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.learning.governance.ParameterResolver;
import ai.intentoms.learning.hooks.NodeReliabilityFeatures;
import ai.intentoms.learning.learner.BacklogBreachCurve;
import ai.intentoms.learning.learner.BacklogBreachCurveLearner;
import ai.intentoms.learning.learner.FulfilmentFacts;
import ai.intentoms.learning.learner.LaneDeliveryLearner;
import ai.intentoms.learning.learner.SourcingPolicyBandit;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.LearnedFactCache;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.order.model.Address;
import ai.intentoms.order.model.FulfilmentGroup;
import ai.intentoms.order.model.Order;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.sourcing.NodeRegistry;
import ai.intentoms.sourcing.PromiseService;
import ai.intentoms.sourcing.SourcingService;
import ai.intentoms.sourcing.SourcingTestBase;
import ai.intentoms.sourcing.learning.LaneKnowledge;
import ai.intentoms.sourcing.model.Policies;
import ai.intentoms.sourcing.model.Promise;
import ai.intentoms.sourcing.model.PromiseRequest;
import ai.intentoms.sourcing.model.SourcingResult;
import ai.intentoms.sourcing.promise.DefaultPromiseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Learned fulfilment values on the live sourcing and promise paths, as the requirements doc words them: FS-01 (the
 * bandit's context and arms, and the logged arm is the policy used), FS-03 (service-specific lanes that feed plans and
 * the order's promise date, and never make a promise later on thin evidence), FS-05 (load shifts before a breach),
 * CX-01 (calibration on the probability stored on the order) and IU-03 (tie-break tolerance from the objective).
 */
class FulfilmentLiveQualityIntegrationTest extends SourcingTestBase {
    static final Address AUSTIN = new Address("ship_to", "Jane", "Congress Ave", null, "Austin", "TX", "78701", "US", null, "residential", true, null, null);

    @Autowired SourcingService sourcing;
    @Autowired PromiseService promise;
    @Autowired DefaultPromiseService promiseImpl;
    @Autowired NodeRegistry nodes;
    @Autowired FulfilmentFacts facts;
    @Autowired LearnedFactRepository factRepo;
    @Autowired LearnedFactCache factCache;
    @Autowired ParameterResolver parameters;
    @Autowired DecisionRecordRepository decisionRecords;
    @Autowired LaneKnowledge lanes;
    final Actor actor = Actor.user("tester");

    /** FS-03 / defect 16: a thin lane that looks slower never moves a promise later; enough evidence of a faster lane moves it earlier. */
    @Test
    void learnedLaneTransitFeedsPlansAndPromisesButNeverLengthensOnThinEvidence() {
        stock("TEE-BLK-M", "DC-TX", 50);
        stock("TEE-BLK-M", "VND-MX", 50);
        config.set("sourcing.service_carriers", Map.of("ground", "UPS"));
        PromiseRequest req = new PromiseRequest(List.of(new PromiseRequest.PromiseLine("TEE-BLK-M", 1)), "78701", "TX", "web", "ground");
        Promise staticPromise = promise.promise(req);
        assertThat(lanes.transit("ground", "DC-TX", "787", 2).source()).isEqualTo("STATIC");

        // Ten deliveries say DC-TX -> 787 by UPS ground takes four days: too few to make a promise later than the rate card.
        String lane = LaneDeliveryLearner.laneKey("UPS", "ground", "DC-TX", "787");
        facts.number(SubjectType.LANE, lane, FactKey.LANE_TRANSIT_DAYS_P50, 4.0, 10, LaneDeliveryLearner.KEY, 0.01);
        parameters.promote(LaneDeliveryLearner.TRANSIT_PARAMETER, LaneDeliveryLearner.KEY, 0.0, 10.0, null, null, "test");
        factCache.invalidateAll();
        promiseImpl.invalidateAll();
        Promise thin = promise.promise(req);
        assertThat(thin.date()).isEqualTo(staticPromise.date());
        assertThat(thin.fromNodeId()).isEqualTo(staticPromise.fromNodeId());
        assertThat(lanes.transit("ground", "DC-TX", "787", 2).reason()).contains("only 10 deliveries");

        // Plans read the same lane: the order's promise date is the static one too.
        Order o1 = order("web", null, AUSTIN, line("L1", "TEE-BLK-M", 1, 28, 7.5));
        SourcingResult r1 = sourcing.source(o1.id(), actor);
        var staticEta = r1.plan().latestEta();

        // A faster lane with enough deliveries replaces the rate card, in the promise and in the order's plan.
        facts.number(SubjectType.LANE, lane, FactKey.LANE_TRANSIT_DAYS_P50, 0.5, 30, LaneDeliveryLearner.KEY, 0.01);
        String mxLane = LaneDeliveryLearner.laneKey("UPS", "ground", "VND-MX", "787");
        facts.number(SubjectType.LANE, mxLane, FactKey.LANE_TRANSIT_DAYS_P50, 9.0, 30, LaneDeliveryLearner.KEY, 0.01);
        factCache.invalidateAll();
        promiseImpl.invalidateAll();
        LaneKnowledge.Transit learned = lanes.transit("ground", "DC-TX", "787", 2);
        assertThat(learned.source()).isEqualTo("LEARNED");
        assertThat(learned.days()).isEqualTo(1);
        Promise fast = promise.promise(req);
        assertThat(fast.date()).isBeforeOrEqualTo(staticPromise.date());
        assertThat(fast.fromNodeId()).isEqualTo("DC-TX");
        Order o2 = order("web", null, AUSTIN, line("L1", "TEE-BLK-M", 1, 28, 7.5));
        SourcingResult r2 = sourcing.source(o2.id(), actor);
        assertThat(r2.plan().packages().get(0).nodeId()).isEqualTo("DC-TX");
        assertThat(r2.plan().latestEta()).isBeforeOrEqualTo(staticEta);
        assertThat(r2.plan().packages().get(0).transitDays()).isLessThanOrEqualTo(r1.plan().packages().get(0).transitDays());
        FulfilmentGroup g = orders.get(o2.id()).fulfilmentGroups().get(0);
        assertThat(g.promiseDate()).isEqualTo(r2.plan().latestEta());
    }

    /** CX-01: lane calibration reaches the probability stored on the order; order.sourced keeps the pre-calibration value for learning. */
    @Test
    void laneCalibrationIsAppliedToTheOrdersStatedProbability() {
        stock("TEE-BLK-M", "DC-NJ", 50);
        config.set("sourcing.service_carriers", Map.of("ground", "UPS"));
        Order before = order("web", null, NEW_YORK, line("L1", "TEE-BLK-M", 1, 28, 7.5));
        double staticOnTime = sourcing.source(before.id(), actor).plan().packages().get(0).onTimeProbability();

        facts.number(SubjectType.LANE, LaneDeliveryLearner.laneKey("UPS", "ground", "DC-NJ", "100"), FactKey.LANE_CALIBRATION_OFFSET, -0.12, 40, LaneDeliveryLearner.KEY, 0.001);
        parameters.promote(LaneDeliveryLearner.CALIBRATION_PARAMETER, LaneDeliveryLearner.KEY, -0.3, 0.3, null, null, "test");
        factCache.invalidateAll();
        Order after = order("web", null, NEW_YORK, line("L1", "TEE-BLK-M", 1, 28, 7.5));
        sourcing.source(after.id(), actor);
        FulfilmentGroup g = orders.get(after.id()).fulfilmentGroups().get(0);
        assertThat(g.onTimeProbability()).isCloseTo(staticOnTime - 0.12, within(0.001));
        @SuppressWarnings("unchecked") Map<String, Object> pkg = ((List<Map<String, Object>>) events("order", after.id(), DefaultSourcingService.EVT_SOURCED).get(0).get("packages")).get(0);
        assertThat(((Number) pkg.get("onTimeBase")).doubleValue()).isCloseTo(staticOnTime, within(0.001));
        assertThat(pkg).containsKeys("shipBy", "dayPart");
    }

    /** FS-05: a node whose learned curve says a breach is likely at its backlog loses the load before the breach. */
    @Test
    void sourcingShiftsLoadAwayFromANodeLikelyToBreach() {
        stock("TEE-BLK-M", "DC-TX", 50);
        stock("TEE-BLK-M", "ST-AUS-03", 50);
        Order plain = order("marketplace", null, DALLAS, line("L1", "TEE-BLK-M", 1, 28, 7.5));
        assertThat(sourcing.source(plain.id(), actor).plan().packages().get(0).nodeId()).isEqualTo("DC-TX");

        nodes.updateHealth("DC-TX", Map.of("backlog", 9000));
        facts.list(SubjectType.NODE, "DC-TX", FactKey.NODE_BACKLOG_BREACH_CURVE,
            BacklogBreachCurve.encode(List.of(new BacklogBreachCurve.Point(500, 0.05), new BacklogBreachCurve.Point(5000, 0.6), new BacklogBreachCurve.Point(9000, 0.85))),
            60, BacklogBreachCurveLearner.KEY);
        factCache.invalidateAll();
        Order notLive = order("marketplace", null, DALLAS, line("L1", "TEE-BLK-M", 1, 28, 7.5));
        assertThat(sourcing.source(notLive.id(), actor).plan().packages().get(0).nodeId()).isEqualTo("DC-TX");

        parameters.promote(NodeReliabilityFeatures.BACKLOG_BREACH, BacklogBreachCurveLearner.KEY, 0.0, 1.0, null, null, "test");
        Order shifted = order("marketplace", null, DALLAS, line("L1", "TEE-BLK-M", 1, 28, 7.5));
        SourcingResult r = sourcing.source(shifted.id(), actor);
        assertThat(r.plan().packages().get(0).nodeId()).isEqualTo("ST-AUS-03");
        assertThat(r.reasons()).anyMatch(s -> s.startsWith("load shifted away"));
        assertThat(r.plan().explain()).containsKey("loadShift");
    }

    /** FS-01: a ship-to order is never offered store pickup; the context carries segment and load; exploration is the tenant's, capped at 10%. */
    @Test
    void banditContextArmsAndExplorationFollowTheRequirement() {
        stock("TEE-BLK-M", "DC-TX", 50);
        stock("TEE-BLK-M", "ST-AUS-03", 50);
        String customer = "cust-1";
        factRepo.insert(new ai.intentoms.learning.model.LearnedFact(Ids.prefixed("fact"), SubjectType.CUSTOMER, customer, FactKey.SEGMENT_MEMBERSHIP,
            new TypedValue.TextValue("seg-bargain"), 0.9, 40, "ci_segment_discovery", 1, java.time.Instant.now(), null, java.time.Instant.now()));
        config.set(SourcingPolicyBandit.EXPLORATION_KEY, 0.5);
        factCache.invalidateAll();
        Order o = order("web", null, AUSTIN, line("L1", "TEE-BLK-M", 1, 28, 7.5));
        // A pickup plan exists (a BOPIS store nearby), but a ship-to order's arms never include it.
        assertThat(sourcing.options(o.id())).anyMatch(p -> Policies.PICKUP_CONSOLIDATED.equals(p.policy()));
        sourcing.source(o.id(), actor);
        DecisionRecord rec = decisionRecords.forSubject("order", o.id()).stream().filter(d -> SourcingPolicyBandit.DECISION_NAME.equals(d.decision())).findFirst().orElseThrow();
        assertThat(rec.arms()).doesNotContain(Policies.PICKUP_CONSOLIDATED);
        assertThat(String.valueOf(rec.details().get("bucket"))).contains("|seg-bargain|");
        assertThat(String.valueOf(rec.details().get("bucket"))).matches(".*\\|(calm|busy|strained|any)$");
        assertThat(((Number) rec.details().get("exploration")).doubleValue()).isEqualTo(0.10);   // capped
        config.set(SourcingPolicyBandit.EXPLORATION_KEY, 0.04);
        Order o2 = order("web", null, AUSTIN, line("L1", "TEE-BLK-M", 1, 28, 7.5));
        sourcing.source(o2.id(), actor);
        DecisionRecord rec2 = decisionRecords.forSubject("order", o2.id()).stream().filter(d -> SourcingPolicyBandit.DECISION_NAME.equals(d.decision())).findFirst().orElseThrow();
        assertThat(((Number) rec2.details().get("exploration")).doubleValue()).isEqualTo(0.04);
        // The logged arm is the policy the order was finally sourced with.
        assertThat(rec2.chosenArm()).isEqualTo(orders.get(o2.id()).fulfilmentGroups().get(0).policy());
    }
}
