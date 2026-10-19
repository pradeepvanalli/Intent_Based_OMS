package ai.intentoms.learning.learner;

import ai.intentoms.decision.model.Band;
import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.Layer;
import ai.intentoms.decision.model.Mode;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.decision.runtime.DecisionLearningHooks;
import ai.intentoms.learning.governance.ParameterResolver;
import ai.intentoms.learning.hooks.NodeReliabilityFeatures;
import ai.intentoms.learning.model.Episode;
import ai.intentoms.learning.model.EpisodeStatus;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.OutcomeData;
import ai.intentoms.learning.model.OutcomeRecord;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.EpisodeRepository;
import ai.intentoms.learning.store.LearnedFactCache;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.learning.store.OutcomeRepository;
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
 * FS-01..FS-06, CX-01..CX-04 learners through the real Spring context and database: ledger events and rewarded episodes
 * in, versioned facts out, and the facts reaching a live decision input only once their parameter family is promoted.
 * Each test runs in its own tenant so tenant-wide sweeps see only their own data.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class FulfilmentLearningIntegrationTest extends PostgresTestBase {
    @Autowired NodeReliabilityLearner nodeLearner;
    @Autowired LaneDeliveryLearner laneLearner;
    @Autowired BacklogBreachCurveLearner curveLearner;
    @Autowired PromiseBufferLearner bufferLearner;
    @Autowired PromiseConversionRepository conversions;
    @Autowired BanditRewardLearner banditRewards;
    @Autowired SourcingPolicyBandit bandit;
    @Autowired WarnEarlyThresholdLearner warnEarly;
    @Autowired AppeasementLevelLearner appeasement;
    @Autowired LearnedFactRepository facts;
    @Autowired LearnedFactCache factCache;
    @Autowired EpisodeRepository episodes;
    @Autowired OutcomeRepository outcomes;
    @Autowired DecisionRecordRepository decisionRecords;
    @Autowired DecisionLearningHooks hooks;
    @Autowired ParameterResolver parameters;

    String tenant;

    @BeforeEach
    void ownTenant() {
        tenant = "f2_" + Ids.shortId();
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

    /** 24 orders from node N at 09:00 UTC (morning), apparel, to ZIP3 100: low-backlog orders arrive on time, 8 of 12 high-backlog ones late. */
    private String deliverBatch() {
        String node = "N-" + Ids.shortId();
        LocalDate day = LocalDate.now(ZoneOffset.UTC).minusDays(10);
        for (int i = 0; i < 24; i++) {
            String order = "ord-" + Ids.shortId();
            boolean highBacklog = i >= 12;
            boolean late = highBacklog && i < 20;
            Instant sourcedAt = day.atTime(9, 0).toInstant(ZoneOffset.UTC);
            LocalDate eta = late ? day.plusDays(1) : day.plusDays(3);
            Map<String, Object> pkg = new LinkedHashMap<>(Map.of("nodeId", node, "eta", eta.toString(), "onTime", 0.95, "skuClass", "apparel", "backlogUnits", highBacklog ? 80 : 20));
            nodeLearner.on(event("order.sourced", order, Map.of("packages", List.of(pkg), "destinationZip3", "100", "shipMethod", "ground"), sourcedAt));
            laneLearner.on(event("order.shipped", order, Map.of("carrier", "UPS", "nodeId", node), day.atTime(10, 0).toInstant(ZoneOffset.UTC)));
            Event delivered = event("order.delivered", order, Map.of("onTime", !late), day.plusDays(2).atTime(10, 0).toInstant(ZoneOffset.UTC));
            nodeLearner.on(delivered);
            laneLearner.on(delivered);
            laneLearner.on(delivered); // redelivery counts once
        }
        String rejected = "ord-" + Ids.shortId();
        nodeLearner.on(event("order.sourced", rejected, Map.of("packages", List.of(Map.of("nodeId", node, "eta", day.plusDays(3).toString(), "skuClass", "apparel"))), day.atTime(9, 0).toInstant(ZoneOffset.UTC)));
        nodeLearner.on(event("order.resourced", rejected, Map.of("rejectedNodes", List.of(node)), day.atTime(11, 0).toInstant(ZoneOffset.UTC)));
        return node;
    }

    @Test
    void deliveriesBecomeNodeLaneAndBacklogFactsThatReachDecisionsOnlyWhenPromoted() {
        String node = deliverBatch();

        // FS-02: node overall and node x SKU class x day part
        assertThat(number(SubjectType.NODE, node, FactKey.NODE_ON_TIME_RATE)).isCloseTo((16 + 1.0) / (24 + 2), within(0.001));
        assertThat(facts.active(SubjectType.NODE, node + "|apparel|morning", FactKey.NODE_ON_TIME_RATE)).isPresent();
        assertThat(facts.active(SubjectType.NODE, node + "|apparel", FactKey.NODE_REJECT_RATE)).isPresent();
        // FS-06: one node, so its reject rate equals the fleet's and the penalty equals the static 0.3
        assertThat(number(SubjectType.NODE, node, FactKey.NODE_REJECT_PENALTY)).isCloseTo(0.3, within(0.001));
        // FS-03 + CX-01: ZIP3 lane and the coarse lane, each counted once per delivery
        String lane = LaneDeliveryLearner.laneKey("UPS", "ground", node, "100");
        assertThat(number(SubjectType.LANE, lane, FactKey.LANE_TRANSIT_DAYS_P50)).isCloseTo(2.0, within(0.01));
        // A stable value is republished as evidence grows (so minimum-sample checks are met), never past the 24 deliveries.
        assertThat(facts.active(SubjectType.LANE, lane, FactKey.LANE_TRANSIT_DAYS_P50).orElseThrow().sampleSize()).isBetween(20, 24);
        assertThat(number(SubjectType.LANE, lane, FactKey.LANE_CALIBRATION_OFFSET)).isCloseTo(16.0 / 24 - 0.95, within(0.001));
        assertThat(facts.active(SubjectType.LANE, LaneDeliveryLearner.laneKey("UPS", "ground", node, null), FactKey.LANE_ON_TIME_RATE)).isPresent();

        // FS-05: backlog-to-breach curve, rising with backlog
        curveLearner.sweepOnce();
        var curve = BacklogBreachCurve.parse(facts.active(SubjectType.NODE, node, FactKey.NODE_BACKLOG_BREACH_CURVE).orElseThrow());
        assertThat(BacklogBreachCurve.at(curve, 80).orElseThrow()).isGreaterThan(BacklogBreachCurve.at(curve, 20).orElseThrow());

        // Not promoted for live use: the decision input keeps its static values, the facts consulted are still recorded.
        factCache.invalidateAll();
        Map<String, Object> input = Map.of("order", Map.of("sku_class", "apparel", "day_part", "morning"),
            "candidates", List.of(new LinkedHashMap<>(Map.of("id", node, "reject_rate", 0.01, "backlog_units", 80))));
        var before = hooks.contribute(NodeReliabilityFeatures.DECISION, input, DecisionOptions.live("order", "o1", Actor.system("test")));
        Map<?, ?> cand = (Map<?, ?>) ((List<?>) before.input().get("candidates")).get(0);
        assertThat(cand.get("reject_rate")).isEqualTo(0.01);
        assertThat(cand.containsKey("on_time_rate")).isFalse();
        assertThat(before.factsUsed()).isNotEmpty().allMatch(f -> "STATIC".equals(f.get("source")));
        assertThat(before.factsUsed()).anyMatch(f -> (node + "|apparel|morning").equals(f.get("subjectId")));

        // Promoted: learned values are served (the most specific fact for reliability, the curve for breach risk).
        parameters.promote(NodeReliabilityFeatures.REJECT_RATE, NodeReliabilityLearner.KEY, 0.0, 1.0, null, null, "test");
        parameters.promote(NodeReliabilityFeatures.ON_TIME_RATE, NodeReliabilityLearner.KEY, 0.0, 1.0, null, null, "test");
        parameters.promote(NodeReliabilityFeatures.BACKLOG_BREACH, BacklogBreachCurveLearner.KEY, 0.0, 1.0, null, null, "test");
        var after = hooks.contribute(NodeReliabilityFeatures.DECISION, input, DecisionOptions.live("order", "o1", Actor.system("test")));
        Map<?, ?> live = (Map<?, ?>) ((List<?>) after.input().get("candidates")).get(0);
        assertThat(live.get("reject_rate")).isEqualTo(number(SubjectType.NODE, node + "|apparel|morning", FactKey.NODE_REJECT_RATE));
        assertThat(live.get("on_time_rate")).isEqualTo(number(SubjectType.NODE, node + "|apparel|morning", FactKey.NODE_ON_TIME_RATE));
        assertThat((Double) live.get("breach_probability")).isGreaterThan(0.3);
        assertThat(after.factsUsed()).allMatch(f -> "LEARNED".equals(f.get("source")));
        // The caller's own input was never touched.
        assertThat(((Map<?, ?>) ((List<?>) input.get("candidates")).get(0)).get("reject_rate")).isEqualTo(0.01);
    }

    @Test
    void conversionsBecomeAPromiseBufferPerSegment() {
        for (int i = 0; i < 40; i++) conversions.record("i0-" + i, "channel:web", null, 3, 0, i < 24, Instant.now());
        for (int i = 0; i < 40; i++) conversions.record("i2-" + i, "channel:web", null, 5, 2, i < 26, Instant.now());
        conversions.record("i2-0", "channel:web", null, 5, 2, false, Instant.now()); // retried impression is ignored
        assertThat(bufferLearner.sweepOnce()).isGreaterThan(0);
        assertThat(number(SubjectType.SEGMENT, "channel:web", FactKey.PROMISE_BUFFER_DAYS)).isEqualTo(2.0);
        assertThat(number(SubjectType.SEGMENT, "default", FactKey.PROMISE_BUFFER_DAYS)).isEqualTo(2.0);
    }

    @Test
    void banditChoicesAndRewardsBecomeBucketWeights() {
        parameters.promote(SourcingPolicyBandit.LIVE_PARAMETER, SourcingPolicyBandit.LEARNER_KEY, null, null, null, null, "test");
        Map<String, Object> ctx = Map.of("channel", "web", "intentPolicy", "ship_as_available", "basketUnits", 2, "destinationZip", "10001");
        List<String> arms = List.of("ship_as_available", "ship_complete", "split_up_to_k");
        for (int i = 0; i < 30; i++) {
            String order = "ord-" + Ids.shortId();
            var choice = bandit.choose(order, arms, "ship_as_available", ctx, "system:test");
            DecisionRecord rec = decisionRecords.find(choice.decisionRecordId()).orElseThrow();
            assertThat(rec.chosenArm()).isEqualTo(choice.policy());
            assertThat(rec.propensity()).isEqualTo(choice.propensity());
            assertThat(rec.details()).containsEntry("bucket", "web|ship_as_available|2-3|z1|none|any");
            Episode ep = Episode.open(Ids.prefixed("epi"), rec.id(), SourcingPolicyBandit.DECISION_NAME, order, null, null, 1, "order", order, Instant.now());
            episodes.upsertDecisionPoint(ep);
            episodes.updateStatusAndReward(episodes.findByDecisionId(rec.id()).orElseThrow().episodeId(), EpisodeStatus.PROVISIONAL,
                "ship_complete".equals(choice.policy()) ? 0.9 : 0.4, Map.of("otd", 1.0), null);
        }
        assertThat(banditRewards.sweepOnce()).isGreaterThan(0);
        LearnedFact bucket = facts.active(SubjectType.SEGMENT, SourcingPolicyBandit.bucketSubject("web|ship_as_available|2-3|z1|none|any"), FactKey.SOURCING_POLICY_PROPENSITY).orElseThrow();
        assertThat(facts.active(SubjectType.SEGMENT, SourcingPolicyBandit.bucketSubject("web|ship_as_available|2-3|z1"), FactKey.SOURCING_POLICY_PROPENSITY)).isPresent();
        Map<String, Double> w = SourcingPolicyBandit.parseWeights(((TypedValue.ListValue) bucket.value()).values());
        assertThat(w.values().stream().mapToDouble(Double::doubleValue).sum()).isCloseTo(1.0, within(0.01));
        assertThat(facts.active(SubjectType.SEGMENT, "default", FactKey.SOURCING_POLICY_PROPENSITY)).isPresent();
        // The next choice reads the bucket's weights.
        var next = bandit.choose("ord-" + Ids.shortId(), arms, "ship_as_available", ctx, "system:test");
        assertThat(String.valueOf(decisionRecords.find(next.decisionRecordId()).orElseThrow().details().get("weightsFrom"))).startsWith("bandit:web|");
    }

    @Test
    void lateOrdersTeachTheWarnEarlyThreshold() {
        for (int i = 0; i < 40; i++) {
            String order = "ord-" + Ids.shortId();
            boolean late = i < 12;
            int score = late ? (i < 8 ? 3 : 2) : (i < 16 ? 2 : 1);
            DecisionRecord r = record("sla_breach_risk", order, score, Map.of());
            Episode ep = Episode.open(Ids.prefixed("epi"), r.id(), "sla_breach_risk", order, null, null, 1, "order", order, Instant.now());
            episodes.upsertDecisionPoint(ep);
            String epId = episodes.findByDecisionId(r.id()).orElseThrow().episodeId();
            outcomes.insert(OutcomeRecord.of(Ids.prefixed("out"), epId, new OutcomeData.DeliveryOutcome(!late, late ? 30 : 0, false, false), Instant.now(), Ids.id(), Instant.now()));
        }
        assertThat(warnEarly.sweepOnce()).isEqualTo(1);
        assertThat(number(SubjectType.DECISION, "sla_breach_risk", FactKey.WARN_EARLY_THRESHOLD)).isEqualTo(2.0);
    }

    @Test
    void repeatComplaintsTeachTheAppeasementLevel() {
        for (int i = 0; i < 36; i++) {
            String order = "ord-" + Ids.shortId();
            String given = i < 18 ? "none" : "credit_10";
            boolean repeat = i < 18 ? i < 9 : i < 20;
            DecisionRecord r = record("appeasement", order, given, Map.of("customer", Map.of("tier", "gold"), "order", Map.of("shipping_paid", 7.5)));
            Instant at = Instant.now().minusSeconds(3600);
            Episode ep = Episode.open(Ids.prefixed("epi"), r.id(), "appeasement", order, null, null, 1, "order", order, at);
            episodes.upsertDecisionPoint(ep);
            String epId = episodes.findByDecisionId(r.id()).orElseThrow().episodeId();
            if (repeat) outcomes.insert(OutcomeRecord.of(Ids.prefixed("out"), epId, new OutcomeData.ContactOutcome("email", "complaint", true), Instant.now(), Ids.id(), Instant.now()));
        }
        assertThat(appeasement.sweepOnce()).isGreaterThan(0);
        assertThat(number(SubjectType.SEGMENT, "tier:gold", FactKey.APPEASEMENT_LEVEL)).isEqualTo(10.0);
    }

    private DecisionRecord record(String decision, String orderId, Object value, Map<String, Object> input) {
        DecisionRecord r = new DecisionRecord(Ids.prefixed("dr"), decision, 1, Mode.LIVE, "order", orderId, orderId, Layer.L1_SYSTEM_ONE, Band.ACT, "act",
            value, Map.of(), 0.9, "test", "v1", Ids.id(), Map.of(), input, List.of(), List.of(), 1, 0, 0, false, null, "system:test",
            Map.of(), null, null, null, null, Map.of(), Instant.now(), Map.of(), List.of(String.valueOf(value)), String.valueOf(value), 1.0, 1);
        decisionRecords.insert(r);
        return r;
    }
}
