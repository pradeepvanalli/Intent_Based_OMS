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
    @Autowired ai.intentoms.sourcing.api.PromiseCalibrationController calibration;
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

    @Autowired ai.intentoms.learning.learner.LaneDeliverySampleRepository laneSamples;

    /** Deliveries on a lane: each told {@code base} before lane calibration (and that, uncalibrated, as stated), {@code onTime} of them on time. */
    private void deliveries(String lane, int n, int onTime, double base) {
        for (int i = 0; i < n; i++) laneSamples.record(lane, 2.0, i < onTime, base, base, "ord-" + Ids.shortId(), "N");
    }

    /**
     * CX-01, measured out of sample (audit B3-2). The "drift" lane was told 95% throughout; 90% of its older deliveries and
     * 70% of its newer ones arrived on time. Learned on all 80 the correction is -15 points and, measured on the same 80,
     * the residual is 0 by construction (the old report called it within target). Learned on the older half (-5) and
     * measured on the newer half it is 20 points off, and that is what the report must say. A stable lane is within
     * target out of sample; a lane needing +42.5 points under a +/-30 range says it is clamped; a lane whose newer half has
     * fewer than {@code minSamples} deliveries is listed but does not count.
     */
    @Test
    void calibrationIsMeasuredOutOfSampleAndClampsAreReported() {
        String drift = LaneDeliveryLearner.laneKey("UPS", "ground", "DC-NJ", "100");
        String stable = LaneDeliveryLearner.laneKey("UPS", "ground", "DC-TX", "787");
        String capped = LaneDeliveryLearner.laneKey("USPS", "ground", "ST-CHI-01", "606");
        String thin = LaneDeliveryLearner.laneKey("FDX", "ground", "DC-TX", "787");
        deliveries(drift, 40, 36, 0.95);
        deliveries(stable, 40, 32, 0.90);
        deliveries(capped, 40, 37, 0.50);
        deliveries(thin, 12, 6, 0.60);
        sleep(5);
        deliveries(drift, 40, 28, 0.95);
        deliveries(stable, 40, 32, 0.90);
        deliveries(capped, 40, 37, 0.50);
        deliveries(thin, 12, 6, 0.60);
        // What the lane learner writes from those same 80 deliveries.
        facts.number(SubjectType.LANE, drift, FactKey.LANE_CALIBRATION_OFFSET, -0.15, 80, LaneDeliveryLearner.KEY, 0.001);
        facts.number(SubjectType.LANE, drift, FactKey.LANE_CALIBRATION_ERROR, -0.15, 80, LaneDeliveryLearner.KEY, 0.001);

        // Not promoted: nothing is served, so the newer drift deliveries were 25 points below what they were told.
        factCache.invalidateAll();
        assertThat(lane(calibration.calibration(30), drift)).containsEntry("residualPoints", -25.0).containsEntry("servedCorrectionPoints", 0.0)
            .containsEntry("correctionSource", "STATIC").containsEntry("withinTarget", false);

        parameters.promote(LaneDeliveryLearner.CALIBRATION_PARAMETER, LaneDeliveryLearner.KEY, -0.3, 0.3, null, null, "test");
        factCache.invalidateAll();
        Map<String, Object> r = calibration.calibration(30);
        assertThat(r).containsEntry("targetPoints", 3.0).containsEntry("minSamples", 30).containsEntry("method", "out_of_sample").containsEntry("lanesMeasured", 4)
            .containsEntry("lanesCounted", 3).containsEntry("lanesWithinTarget", 1).containsEntry("lanesClamped", 1);
        assertThat(lane(r, drift)).containsEntry("neededCorrectionPoints", -5.0).containsEntry("servedCorrectionPoints", -5.0).containsEntry("residualPoints", -20.0)
            .containsEntry("toldResidualPoints", -25.0).containsEntry("servedNowCorrectionPoints", -15.0).containsEntry("learnedOn", 40L).containsEntry("measuredOn", 40L)
            .containsEntry("countsTowardTarget", true).containsEntry("withinTarget", false).doesNotContainKey("clamped");
        assertThat(lane(r, stable)).containsEntry("residualPoints", 0.0).containsEntry("withinTarget", true);
        assertThat(lane(r, capped)).containsEntry("neededCorrectionPoints", 42.5).containsEntry("servedCorrectionPoints", 30.0).containsEntry("residualPoints", 12.5)
            .containsEntry("clamped", true).containsEntry("withinTarget", false);
        assertThat(String.valueOf(lane(r, capped).get("clampReason"))).contains("above the promoted maximum");
        assertThat(lane(r, thin)).containsEntry("samples", 24L).containsEntry("measuredOn", 12L).containsEntry("countsTowardTarget", false).containsEntry("withinTarget", false);
        @SuppressWarnings("unchecked") List<Map<String, Object>> lanes = (List<Map<String, Object>>) r.get("lanes");
        assertThat(lanes.stream().map(m -> m.get("lane")).toList()).containsExactly(drift, capped, stable, thin);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> lane(Map<String, Object> report, String lane) {
        return ((List<Map<String, Object>>) report.get("lanes")).stream().filter(m -> lane.equals(m.get("lane"))).findFirst()
            .orElseThrow(() -> new AssertionError("lane " + lane + " not in " + report));
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

    @Autowired ai.intentoms.learning.learner.NodeAssignmentRepository assignments;

    /**
     * B3-3: "no carrier known yet" is remembered for seconds, not ten minutes, so a node's first shipments start lane
     * transit and calibration at once (a fresh database, a new node or service). Before the fix the first lookup after
     * boot pinned "none" for ten minutes.
     */
    @Test
    void aNodesFirstShipmentNamesItsCarrierWithinSeconds() {
        String node = "N-" + Ids.shortId();
        assertThat(lanes.carrierFor("two_day", node)).isNull();
        String order = "ord-" + Ids.shortId();
        assignments.record(order, node, new ai.intentoms.learning.learner.NodeAssignmentRepository.Sourced("default", null, "100", "two_day", "morning", null, 0.9, null, 0.9),
            java.time.Instant.now());
        assignments.recordShipped(order, node, "UPS", java.time.Instant.now());
        long deadline = System.currentTimeMillis() + 15_000;
        String carrier = null;
        while (carrier == null && System.currentTimeMillis() < deadline) {
            carrier = lanes.carrierFor("two_day", node);
            if (carrier == null) sleep(250);
        }
        assertThat(carrier).as("carrier known within 15 s of the first shipment").isEqualTo("UPS");
    }

    /** CX-02: while the learned buffer is live, a small bounded share of promises try one day more or less, and say so. */
    @Test
    void promiseBufferExplorationIsSmallBoundedAndReported() {
        stock("TEE-BLK-M", "DC-NJ", 50);
        facts.number(SubjectType.SEGMENT, ai.intentoms.learning.learner.PromiseBufferLearner.segment("web"), FactKey.PROMISE_BUFFER_DAYS, 2, 100,
            ai.intentoms.learning.learner.PromiseBufferLearner.KEY, 0.5);
        config.set(ai.intentoms.learning.learner.PromiseBufferLearner.EXPLORATION_KEY, 0.5);   // asks for 50%, gets the 10% cap
        factCache.invalidateAll();
        PromiseRequest req = new PromiseRequest(List.of(new PromiseRequest.PromiseLine("TEE-BLK-M", 1)), "10001", "NY", "web", "ground");
        for (int i = 0; i < 50; i++) { promiseImpl.invalidateAll(); assertThat(promise.promise(req).bufferExplored()).isFalse(); } // not live: never explores

        parameters.promote(ai.intentoms.learning.learner.PromiseBufferLearner.PARAMETER, ai.intentoms.learning.learner.PromiseBufferLearner.KEY, 0.0, 5.0, null, null, "test");
        int explored = 0;
        for (int i = 0; i < 400; i++) {
            promiseImpl.invalidateAll();
            Promise p = promise.promise(req);
            if (p.bufferExplored()) { explored++; assertThat(p.bufferDays()).isIn(1, 3); } else assertThat(p.bufferDays()).isEqualTo(2);
        }
        assertThat(explored).isBetween(10, 80);   // about 10% of 400
    }

    /** B-5: the buffer family promoted with nothing learned yet: the static buffer is served as is and never explored. */
    @Test
    void noBufferExplorationWithoutALearnedBuffer() {
        stock("TEE-BLK-M", "DC-NJ", 50);
        config.set(ai.intentoms.learning.learner.PromiseBufferLearner.EXPLORATION_KEY, 0.5);
        parameters.promote(ai.intentoms.learning.learner.PromiseBufferLearner.PARAMETER, ai.intentoms.learning.learner.PromiseBufferLearner.KEY, 0.0, 5.0, null, null, "test");
        factCache.invalidateAll();
        PromiseRequest req = new PromiseRequest(List.of(new PromiseRequest.PromiseLine("TEE-BLK-M", 1)), "10001", "NY", "web", "ground");
        for (int i = 0; i < 200; i++) {
            promiseImpl.invalidateAll();
            Promise p = promise.promise(req);
            assertThat(p.bufferExplored()).isFalse();
            assertThat(p.bufferDays()).isZero();
        }
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
