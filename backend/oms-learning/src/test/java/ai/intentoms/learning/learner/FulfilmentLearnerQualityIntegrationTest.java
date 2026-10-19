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
            episodes.updateStatusAndReward(episodes.findByDecisionId(rec.id()).orElseThrow().episodeId(), EpisodeStatus.PROVISIONAL, 0.9, Map.of("otd", 1.0), null);
        }
        banditRewards.sweepOnce();
        assertThat(facts.active(SubjectType.SEGMENT, "default", FactKey.SOURCING_POLICY_PROPENSITY)).isEmpty();
        // A draw used as drawn carries its real draw probability.
        var d = bandit.draw(arms, "ship_as_available", ctx);
        var c = bandit.log("ord-" + Ids.shortId(), d, d.applied(), null, "system:test");
        assertThat(c.propensity()).isEqualTo(d.sampledPropensity());
        assertThat(decisionRecords.find(c.decisionRecordId()).orElseThrow().details()).doesNotContainKey("overridden");
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
}
