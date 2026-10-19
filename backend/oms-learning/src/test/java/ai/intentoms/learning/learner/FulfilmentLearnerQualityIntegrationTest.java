package ai.intentoms.learning.learner;

import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.learning.governance.ParameterResolver;
import ai.intentoms.learning.model.Episode;
import ai.intentoms.learning.model.EpisodeStatus;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.EpisodeRepository;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.testing.PostgresTestBase;
import ai.intentoms.testing.TestApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The fulfilment learners as the requirements doc words them: FS-02 learns on-time pick (left the node by the ship-by
 * date) per node, SKU class and node-local day part; FS-06 evidence fades so a node recovers from old rejects; FS-01
 * a draw overridden downstream is logged as such and left out of the bandit's reward; CX-01 the calibration offset is
 * learned against the pre-calibration probability and the residual after calibration is published separately.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class FulfilmentLearnerQualityIntegrationTest extends PostgresTestBase {
    @Autowired NodeReliabilityLearner nodeLearner;
    @Autowired LaneDeliveryLearner laneLearner;
    @Autowired NodeReliabilityStatRepository stats;
    @Autowired SourcingPolicyBandit bandit;
    @Autowired BanditRewardLearner banditRewards;
    @Autowired LearnedFactRepository facts;
    @Autowired EpisodeRepository episodes;
    @Autowired DecisionRecordRepository decisionRecords;
    @Autowired ParameterResolver parameters;
    @Autowired TenantConfigStore config;
    @Autowired PromiseConversionRepository conversions;
    @Autowired WarnEarlyThresholdLearner warnEarly;
    @Autowired ai.intentoms.learning.store.OutcomeRepository outcomes;
    @Autowired NodeAssignmentRepository assignments;

    String tenant;

    @BeforeEach
    void ownTenant() {
        tenant = "e4f_" + Ids.shortId();
        jdbc.sql("insert into tenant(tenant_id, name) values (:t, :t) on conflict do nothing").param("t", tenant).update();
        TenantContext.set(tenant);
    }

    private Event event(String type, String orderId, Map<String, Object> payload, Instant at) {
        return new Event(Ids.id(), tenant, "order", orderId, 1, type, payload, Actor.system("test"), null, orderId, Map.of(), at);
    }

    private double number(SubjectType st, String id, FactKey key) {
        LearnedFact f = facts.active(st, id, key).orElseThrow(() -> new AssertionError("no " + key + " for " + st + ":" + id));
        return ((TypedValue.NumberValue) f.value()).value();
    }

    /** FS-02: picks that left after the ship-by date count as late even when the carrier still delivered on time. */
    @Test
    void nodeReliabilityLearnsOnTimePickNotCarrierDelivery() {
        String node = "N-" + Ids.shortId();
        LocalDate day = LocalDate.now(ZoneOffset.UTC).minusDays(5);
        for (int i = 0; i < 20; i++) {
            String order = "ord-" + Ids.shortId();
            boolean latePick = i < 8;
            Map<String, Object> pkg = new LinkedHashMap<>(Map.of("nodeId", node, "eta", day.plusDays(4).toString(), "onTime", 0.9, "onTimeBase", 0.9,
                "skuClass", "apparel", "shipBy", day.toString(), "dayPart", "evening"));
            nodeLearner.on(event("order.sourced", order, Map.of("packages", List.of(pkg), "destinationZip3", "100", "shipMethod", "ground"), day.atTime(9, 0).toInstant(ZoneOffset.UTC)));
            Instant shipped = (latePick ? day.plusDays(2) : day).atTime(15, 0).toInstant(ZoneOffset.UTC);
            Event ship = event("order.shipped", order, Map.of("nodeId", node, "carrier", "UPS"), shipped);
            nodeLearner.on(ship);
            nodeLearner.on(ship); // a redelivered shipment counts once
            // Every parcel arrives before its ETA: by delivery alone the node would look perfect.
            nodeLearner.on(event("order.delivered", order, Map.of("onTime", true), day.plusDays(3).atTime(12, 0).toInstant(ZoneOffset.UTC)));
        }
        assertThat(number(SubjectType.NODE, node, FactKey.NODE_ON_TIME_RATE)).isCloseTo((12 + 1.0) / (20 + 2), within(0.001));
        // The day part is the node-local one sourcing sent ("evening"), not the UTC hour of the event (09:00 = morning).
        assertThat(facts.active(SubjectType.NODE, node + "|apparel|evening", FactKey.NODE_ON_TIME_RATE)).isPresent();
        assertThat(facts.active(SubjectType.NODE, node + "|apparel|morning", FactKey.NODE_ON_TIME_RATE)).isEmpty();
    }

    /** FS-06: old rejects fade with the half-life, so the node's reject rate (and penalty) follow its recent behaviour. */
    @Test
    void oldRejectsFadeSoANodeRecovers() {
        String recovering = "N-" + Ids.shortId();
        String steady = "N-" + Ids.shortId();
        config.set(NodeReliabilityLearner.HALF_LIFE_KEY, 30.0);
        for (String node : List.of(recovering, steady)) for (int i = 0; i < 10; i++) stats.recordReject(node, "default", "any", true, 30.0);
        // Pretend the rejects happened 90 days ago for one node (three half-lives).
        jdbc.sql("update node_reliability_stat set updated_at = now() - interval '90 days' where tenant_id = :t and node_id = :n").param("t", tenant).param("n", recovering).update();
        for (String node : List.of(recovering, steady)) for (int i = 0; i < 10; i++) stats.recordReject(node, "default", "any", false, 30.0);
        double recovered = stats.posterior(recovering, "default", "any").rejectMean();
        double notRecovered = stats.posterior(steady, "default", "any").rejectMean();
        assertThat(recovered).isLessThan(notRecovered - 0.2);
        assertThat(recovered).isCloseTo((10 * 0.125 + 1) / (10 * 0.125 + 10 + 2), within(0.01));
    }

    /** FS-01: when sourcing ends up using another policy than the bandit's, the record says so and the reward learner ignores it. */
    /** Logs a choice of {@code arm} with probability {@code p}, as the live bandit would after drawing it, and rewards its episode. */
    private String banditChoice(String arm, double p, Map<String, Object> ctx, EpisodeStatus status, double reward) {
        String order = "ord-" + Ids.shortId();
        SourcingPolicyBandit.Draw real = bandit.draw(List.of("ship_as_available", "ship_complete"), "ship_as_available", ctx);
        SourcingPolicyBandit.Draw drawn = new SourcingPolicyBandit.Draw(real.bucket(), real.weights(), real.weightsFrom(), real.exploration(), real.seed(), arm, p, true, arm,
            "ship_as_available", real.eligible(), real.context(), real.gate(), false, real.startedNanos());
        var c = bandit.log(order, drawn, arm, null, "system:test");
        episodes.upsertDecisionPoint(Episode.open(Ids.prefixed("epi"), c.decisionRecordId(), SourcingPolicyBandit.DECISION_NAME, order, null, null, 1, "order", order, Instant.now()));
        String episodeId = episodes.findByDecisionId(c.decisionRecordId()).orElseThrow().episodeId();
        episodes.updateStatusAndReward(episodeId, status, reward, Map.of("otd", 1.0), status == EpisodeStatus.MATURE ? Instant.now() : null);
        return episodeId;
    }

    private Map<String, Double> bucketWeights(String bucket) {
        LearnedFact f = facts.active(SubjectType.SEGMENT, SourcingPolicyBandit.bucketSubject(bucket), FactKey.SOURCING_POLICY_PROPENSITY).orElseThrow();
        return SourcingPolicyBandit.parseWeights(((TypedValue.ListValue) f.value()).values());
    }

    /**
     * B3-4 (FS-01, B-4 residue) as the audit saw it: the default arm has 28 matured rewards around 1.9; two explorations of
     * another arm came back with provisional rewards of 2.149. Provisional rewards must not make that arm the greedy one,
     * not even with ten of them; once five have matured it may win. Exploration (epsilon / n per arm) is unchanged.
     */
    @Test
    void provisionalRewardsCannotMakeAnArmGreedy() {
        Map<String, Object> ctx = Map.of("channel", "web", "intentPolicy", "ship_as_available", "basketUnits", 2, "destinationZip", "60606", "networkLoad", "calm");
        String bucket = SourcingPolicyBandit.bucket(ctx);
        for (int i = 0; i < 28; i++) banditChoice("ship_as_available", 0.95, ctx, EpisodeStatus.MATURE, 1.85 + (i % 3) * 0.05);
        List<String> explored = new java.util.ArrayList<>();
        for (int i = 0; i < 2; i++) explored.add(banditChoice("ship_complete", 0.05, ctx, EpisodeStatus.PROVISIONAL, 2.149));
        banditRewards.sweepOnce();
        Map<String, Double> w = bucketWeights(bucket);
        assertThat(w.get("ship_as_available")).as("weights " + w).isGreaterThan(w.get("ship_complete"));
        SourcingPolicyBandit.Draw next = bandit.draw(List.of("ship_as_available", "ship_complete"), "ship_as_available", ctx);
        assertThat(SourcingPolicyBandit.drawProbability("ship_as_available", next.eligible(), next.weights(), next.exploration())).isCloseTo(0.95, within(1e-9));
        assertThat(SourcingPolicyBandit.drawProbability("ship_complete", next.eligible(), next.weights(), next.exploration())).as("exploration floor").isCloseTo(0.05, within(1e-9));

        // Ten provisional rewards are enough samples, but none is final yet: still not greedy.
        for (int i = 0; i < 8; i++) explored.add(banditChoice("ship_complete", 0.05, ctx, EpisodeStatus.PROVISIONAL, 2.149));
        banditRewards.sweepOnce();
        w = bucketWeights(bucket);
        assertThat(w.get("ship_as_available")).as("weights " + w).isGreaterThan(w.get("ship_complete"));

        // Five of them mature with the same reward: now the arm has proven itself and takes the greedy slot.
        for (String id : explored.subList(0, 5)) episodes.updateStatusAndReward(id, EpisodeStatus.MATURE, 2.149, Map.of("otd", 1.0), Instant.now());
        banditRewards.sweepOnce();
        w = bucketWeights(bucket);
        assertThat(w.get("ship_complete")).as("weights " + w).isGreaterThan(w.get("ship_as_available"));
    }

    @Test
    void anOverriddenDrawIsLoggedAsSuchAndLeftOutOfTheReward() {
        parameters.promote(SourcingPolicyBandit.LIVE_PARAMETER, SourcingPolicyBandit.LEARNER_KEY, null, null, null, null, "test");
        List<String> arms = List.of("ship_as_available", "ship_complete");
        Map<String, Object> ctx = Map.of("channel", "web", "basketUnits", 1, "destinationZip", "10001", "segment", "seg-a", "networkLoad", "calm");
        for (int i = 0; i < 25; i++) {
            String order = "ord-" + Ids.shortId();
            var d = bandit.draw(arms, "ship_as_available", ctx);
            String used = "split_up_to_k"; // e.g. split acceptability or a fallback picked something else
            var c = bandit.log(order, d, used, "split_acceptability preferred split_up_to_k", "system:test");
            DecisionRecord rec = decisionRecords.find(c.decisionRecordId()).orElseThrow();
            assertThat(rec.chosenArm()).isEqualTo(used);
            assertThat(rec.propensity()).isEqualTo(1.0);
            assertThat(rec.details()).containsEntry("overridden", true).containsEntry("banditPolicy", d.applied());
            episodes.upsertDecisionPoint(Episode.open(Ids.prefixed("epi"), rec.id(), SourcingPolicyBandit.DECISION_NAME, order, null, null, 1, "order", order, Instant.now()));
            episodes.updateStatusAndReward(episodes.findByDecisionId(rec.id()).orElseThrow().episodeId(), EpisodeStatus.MATURE, 0.9, Map.of("otd", 1.0), Instant.now());
        }
        banditRewards.sweepOnce();
        assertThat(facts.active(SubjectType.SEGMENT, "default", FactKey.SOURCING_POLICY_PROPENSITY)).isEmpty();
        // A draw used as drawn carries its real draw probability.
        var d = bandit.draw(arms, "ship_as_available", ctx);
        var c = bandit.log("ord-" + Ids.shortId(), d, d.applied(), null, "system:test");
        assertThat(c.propensity()).isEqualTo(d.sampledPropensity());
        assertThat(decisionRecords.find(c.decisionRecordId()).orElseThrow().details()).doesNotContainKey("overridden");
    }

    /** CX-02 / defect 25: a buffer is judged against the date the shopper was shown, so it gets credit for the lateness it absorbs. */
    @Test
    void bufferAccuracyIsMeasuredAgainstThePromiseShown() {
        Instant shown = LocalDate.now(ZoneOffset.UTC).minusDays(10).atTime(10, 0).toInstant(ZoneOffset.UTC);
        for (int i = 0; i < 25; i++) {
            String order = "ord-" + Ids.shortId();
            // Plan ETA was day 3; the shopper was shown day 5 (two days of buffer); the parcel came on day 4.
            nodeLearner.on(event("order.sourced", order, Map.of("packages", List.of(Map.of("nodeId", "N1", "eta", LocalDate.ofInstant(shown, ZoneOffset.UTC).plusDays(3).toString())),
                "destinationZip3", "100", "shipMethod", "ground"), shown));
            nodeLearner.on(event("order.delivered", order, Map.of(), shown.plus(java.time.Duration.ofDays(4))));
            conversions.record("imp-" + order, "channel:web", order, 5, 2, true, shown);
        }
        var stats = conversions.stats(shown.minus(java.time.Duration.ofDays(1)));
        var row = stats.stream().filter(s -> s.bufferDays() == 2).findFirst().orElseThrow();
        assertThat(row.delivered()).isEqualTo(25);
        assertThat(row.onTime()).isEqualTo(25);   // late against the plan ETA, on time against what the shopper was promised
    }

    /** CX-03: the threshold that warns early enough to head off contacts wins over one that only fires once the promise is missed. */
    @Test
    void warnEarlyLearnsTheEarliestThresholdThatAvoidsContacts() {
        LocalDate day = LocalDate.now(ZoneOffset.UTC).minusDays(20);
        for (int i = 0; i < 40; i++) {
            String order = "ord-" + Ids.shortId();
            boolean late = i < 12;
            LocalDate promised = day.plusDays(4);
            nodeLearner.on(event("order.sourced", order, Map.of("packages", List.of(Map.of("nodeId", "N1", "eta", promised.toString())), "destinationZip3", "100"),
                day.atTime(9, 0).toInstant(ZoneOffset.UTC)));
            Instant early = day.plusDays(1).atTime(9, 0).toInstant(ZoneOffset.UTC);           // three days before the promise
            Instant tooLate = promised.plusDays(1).atTime(9, 0).toInstant(ZoneOffset.UTC);    // after the promise was missed
            DecisionRecord first = breachRecord(order, late ? 2 : (i < 15 ? 2 : 1), early);
            if (late) breachRecord(order, 3, tooLate);
            String epi = Ids.prefixed("epi");
            episodes.upsertDecisionPoint(Episode.open(epi, first.id(), WarnEarlyThresholdLearner.DECISION, order, null, null, 1, "order", order, early));
            String epId = episodes.findByDecisionId(first.id()).orElseThrow().episodeId();
            outcomes.insert(ai.intentoms.learning.model.OutcomeRecord.of(Ids.prefixed("out"), epId,
                new ai.intentoms.learning.model.OutcomeData.DeliveryOutcome(!late, late ? 30 : 0, false, false), tooLate, Ids.id(), Instant.now()));
            if (late && i < 8) outcomes.insert(ai.intentoms.learning.model.OutcomeRecord.of(Ids.prefixed("out"), epId,
                new ai.intentoms.learning.model.OutcomeData.ContactOutcome("cs", "wismo", false), tooLate, Ids.id(), Instant.now()));
        }
        assertThat(warnEarly.sweepOnce()).isEqualTo(1);
        assertThat(number(SubjectType.DECISION, WarnEarlyThresholdLearner.DECISION, FactKey.WARN_EARLY_THRESHOLD)).isEqualTo(2.0);
    }

    private DecisionRecord breachRecord(String order, int score, Instant at) {
        DecisionRecord r = new DecisionRecord(Ids.prefixed("dr"), WarnEarlyThresholdLearner.DECISION, 1, ai.intentoms.decision.model.Mode.LIVE, "order", order, order,
            ai.intentoms.decision.model.Layer.L1_SYSTEM_ONE, ai.intentoms.decision.model.Band.ACT, "act", score, Map.of(), 0.9, "test", "v1", Ids.id(), Map.of(), Map.of(),
            List.of(), List.of(), 1, 0, 0, false, null, "system:test", Map.of(), null, null, null, null, Map.of(), at, Map.of(), List.of(String.valueOf(score)),
            String.valueOf(score), 1.0, 1);
        decisionRecords.insert(r);
        return r;
    }

    /** CX-01: offset against the base probability, residual against what the orders were told, so calibration does not chase itself. */
    @Test
    void calibrationOffsetAndResidualAreLearnedSeparately() {
        String node = "N-" + Ids.shortId();
        LocalDate day = LocalDate.now(ZoneOffset.UTC).minusDays(6);
        for (int i = 0; i < 20; i++) {
            String order = "ord-" + Ids.shortId();
            boolean onTime = i < 14; // 70% on time
            // Base said 90%; calibration already lowered what the order was told to 72%.
            Map<String, Object> pkg = new LinkedHashMap<>(Map.of("nodeId", node, "eta", day.plusDays(3).toString(), "onTime", 0.72, "onTimeBase", 0.9, "skuClass", "default"));
            nodeLearner.on(event("order.sourced", order, Map.of("packages", List.of(pkg), "destinationZip3", "606", "shipMethod", "ground"), day.atTime(9, 0).toInstant(ZoneOffset.UTC)));
            laneLearner.on(event("order.shipped", order, Map.of("nodeId", node, "carrier", "UPS"), day.atTime(14, 0).toInstant(ZoneOffset.UTC)));
            Event delivered = event("order.delivered", order, Map.of(), (onTime ? day.plusDays(2) : day.plusDays(5)).atTime(12, 0).toInstant(ZoneOffset.UTC));
            nodeLearner.on(delivered);
            laneLearner.on(delivered);
        }
        String lane = LaneDeliveryLearner.laneKey("UPS", "ground", node, "606");
        assertThat(number(SubjectType.LANE, lane, FactKey.LANE_CALIBRATION_OFFSET)).isCloseTo(0.70 - 0.90, within(0.001));
        assertThat(number(SubjectType.LANE, lane, FactKey.LANE_CALIBRATION_ERROR)).isCloseTo(0.70 - 0.72, within(0.001));
        // Services are never pooled: an express lane from the same node has learned nothing.
        assertThat(facts.active(SubjectType.LANE, LaneDeliveryLearner.laneKey("UPS", "express", node, "606"), FactKey.LANE_TRANSIT_DAYS_P50)).isEmpty();
    }

    /**
     * One order.delivered per fulfilment group: only the group's nodes are credited, a partial scan credits nobody, and
     * a redelivered event for the same group changes nothing.
     */
    @Test
    void aGroupDeliveryCreditsOnlyThatGroupsNode() {
        String nodeA = "NA-" + Ids.shortId();
        String nodeB = "NB-" + Ids.shortId();
        String order = "ord-" + Ids.shortId();
        LocalDate day = LocalDate.now(ZoneOffset.UTC).minusDays(4);
        List<Map<String, Object>> pkgs = List.of(
            Map.of("nodeId", nodeA, "eta", day.plusDays(3).toString(), "onTime", 0.9, "skuClass", "default"),
            Map.of("nodeId", nodeB, "eta", day.plusDays(3).toString(), "onTime", 0.9, "skuClass", "default"));
        nodeLearner.on(event("order.sourced", order, Map.of("packages", pkgs, "destinationZip3", "100", "shipMethod", "ground"), day.atTime(9, 0).toInstant(ZoneOffset.UTC)));
        for (String n : List.of(nodeA, nodeB)) laneLearner.on(event("order.shipped", order, Map.of("nodeId", n, "carrier", "UPS"), day.atTime(14, 0).toInstant(ZoneOffset.UTC)));

        Event partial = event("order.delivered", order, Map.of("orderId", order, "fulfilmentGroupId", "fg1", "nodeIds", List.of(nodeA), "groupDelivered", false,
            "allDelivered", false, "onTime", true), day.plusDays(1).atTime(12, 0).toInstant(ZoneOffset.UTC));
        nodeLearner.on(partial);
        laneLearner.on(partial);
        assertThat(assignments.assignmentsFor(order)).allMatch(a -> a.deliveredAt() == null);

        Event groupA = event("order.delivered", order, Map.of("orderId", order, "fulfilmentGroupId", "fg1", "nodeIds", List.of(nodeA), "groupDelivered", true,
            "allDelivered", false, "onTime", true), day.plusDays(2).atTime(12, 0).toInstant(ZoneOffset.UTC));
        nodeLearner.on(groupA);
        laneLearner.on(groupA);
        var after = assignments.assignmentsFor(order);
        assertThat(after.stream().filter(a -> a.nodeId().equals(nodeA)).findFirst().orElseThrow().deliveredAt()).isNotNull();
        assertThat(after.stream().filter(a -> a.nodeId().equals(nodeB)).findFirst().orElseThrow().deliveredAt()).isNull();
        int creditedA = stats.pooled(nodeA, null).sampleSize();
        assertThat(creditedA).isPositive();
        assertThat(stats.pooled(nodeB, null).sampleSize()).isZero();

        nodeLearner.on(groupA); // redelivered: counted once
        laneLearner.on(groupA);
        Event groupB = event("order.delivered", order, Map.of("orderId", order, "fulfilmentGroupId", "fg2", "nodeIds", List.of(nodeB), "groupDelivered", true,
            "allDelivered", true, "onTime", false), day.plusDays(5).atTime(12, 0).toInstant(ZoneOffset.UTC));
        nodeLearner.on(groupB);
        laneLearner.on(groupB);
        assertThat(stats.pooled(nodeA, null).sampleSize()).isEqualTo(creditedA);
        assertThat(stats.pooled(nodeB, null).sampleSize()).isEqualTo(creditedA);
        Integer laneRows = jdbc.sql("select count(*) from lane_delivery_sample where tenant_id = :t and order_id = :o")
            .param("t", tenant).param("o", order).query(Integer.class).single();
        assertThat(laneRows).isEqualTo(4); // two nodes x (ZIP3 lane + carrier/service/origin lane), each once
        // Node B's parcel arrived after its ETA and is judged on its own group, not on node A's.
        assertThat(assignments.assignmentsFor(order).stream().filter(a -> a.nodeId().equals(nodeB)).findFirst().orElseThrow().deliveredOnTime()).isFalse();
    }
}
