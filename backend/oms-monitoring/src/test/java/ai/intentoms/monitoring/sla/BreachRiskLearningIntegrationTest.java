package ai.intentoms.monitoring.sla;

import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.learning.governance.ParameterResolver;
import ai.intentoms.learning.hooks.NodeReliabilityFeatures;
import ai.intentoms.learning.learner.BacklogBreachCurveLearner;
import ai.intentoms.learning.learner.FulfilmentFacts;
import ai.intentoms.learning.learner.WarnEarlyThresholdLearner;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.store.LearnedFactCache;
import ai.intentoms.monitoring.MonitoringTestBase;
import ai.intentoms.order.model.Order;
import ai.intentoms.platform.json.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** FS-05 and CX-03 on the breach-risk scorer: the learned breach probability as an input, the warn-early trigger. */
class BreachRiskLearningIntegrationTest extends MonitoringTestBase {
    @Autowired BreachRiskScorer scorer;
    @Autowired FulfilmentFacts facts;
    @Autowired LearnedFactCache factCache;
    @Autowired ParameterResolver parameters;
    @Autowired DecisionRecordRepository decisionRecords;

    @AfterEach
    void revoke() {
        parameters.revoke(WarnEarlyThresholdLearner.PARAMETER, "test");
        parameters.revoke(NodeReliabilityFeatures.BACKLOG_BREACH, "test");
        config.delete(WarnEarlyThresholdLearner.STATIC_KEY);
    }

    @Test
    void learnedWarnEarlyThresholdTriggersOneDelayWarningOnlyOncePromoted() {
        // 1 is the lowest threshold the family allows (B-1): it warns every order whose score reaches "some risk".
        facts.number(SubjectType.DECISION, WarnEarlyThresholdLearner.DECISION, FactKey.WARN_EARLY_THRESHOLD, 1, 40, WarnEarlyThresholdLearner.KEY, 0.5);
        factCache.invalidateAll();

        Order quiet = newOrder("web", "released", true);
        backdateState(quiet.id(), Duration.ofHours(6));
        scorer.scoreNow(quiet.id());
        assertThat(events("order", quiet.id(), BreachRiskScorer.DELAY_WARNING)).isEmpty();

        parameters.promote(WarnEarlyThresholdLearner.PARAMETER, WarnEarlyThresholdLearner.KEY, 1.0, 4.0, null, null, "test");
        Order warned = newOrder("web", "released", true);
        backdateState(warned.id(), Duration.ofHours(6));
        scorer.scoreNow(warned.id());
        List<Map<String, Object>> warnings = events("order", warned.id(), BreachRiskScorer.DELAY_WARNING);
        assertThat(warnings).hasSize(1);
        Map<String, Object> payload = Json.readMap((String) warnings.get(0).get("payload"));
        assertThat(payload).containsEntry("thresholdSource", "LEARNED").containsKey("decisionRecordId");

        backdateState(warned.id(), Duration.ofHours(30));   // material change: re-decided, still above the threshold
        scorer.scoreNow(warned.id());
        assertThat(events("order", warned.id(), BreachRiskScorer.DELAY_WARNING)).hasSize(1);
    }

    @Autowired WarnEarlyThresholdLearner warnEarly;
    @Autowired ai.intentoms.learning.store.LearnedFactRepository factRepo;
    @Autowired ai.intentoms.learning.store.EpisodeRepository episodes;
    @Autowired ai.intentoms.learning.store.OutcomeRepository outcomes;

    private void promiseDaysFromNow(Order o, int days) {
        jdbc.sql("update fulfilment_group set promise_date = current_date + :d where tenant_id = :t and order_id = :o").param("d", days).param("t", TENANT).param("o", o.id()).update();
    }

    /** As the demo serves it: the learned 2.0 within the promoted 1.5-3.5. */
    private void demoWarnEarly() {
        facts.number(SubjectType.DECISION, WarnEarlyThresholdLearner.DECISION, FactKey.WARN_EARLY_THRESHOLD, 2, 345, WarnEarlyThresholdLearner.KEY, 0.5);
        parameters.promote(WarnEarlyThresholdLearner.PARAMETER, WarnEarlyThresholdLearner.KEY, 1.5, 3.5, 1.0, null, "test");
        factCache.invalidateAll();
    }

    /**
     * CX-03 (audit B3-6) on the live path with no model key: an unshipped order whose promise date has passed is a clear
     * case, scored 4 by an L0 rule and warned once; an order on track is left alone (the B-1 lesson: no flood).
     */
    @Test
    void aLateUnshippedOrderGetsADelayWarningWithoutAModel() {
        demoWarnEarly();
        Order late = newOrder("web", "released", true);
        promiseDaysFromNow(late, -1);
        OrderRisk r = scorer.scoreNow(late.id()).orElseThrow();
        assertThat(r.score()).isEqualTo(4);
        var record = decisionRecords.find(r.decisionRecordId()).orElseThrow();
        assertThat(record.layer()).isEqualTo(ai.intentoms.decision.model.Layer.L0_RULE);
        assertThat(record.rulesFired()).contains("promise_passed");
        List<Map<String, Object>> warnings = events("order", late.id(), BreachRiskScorer.DELAY_WARNING);
        assertThat(warnings).hasSize(1);
        assertThat(Json.readMap((String) warnings.get(0).get("payload"))).containsEntry("score", 4).containsEntry("thresholdSource", "LEARNED");

        Order onTrack = newOrder("web", "released", true);
        backdateState(onTrack.id(), Duration.ofHours(6));
        assertThat(scorer.scoreNow(onTrack.id()).orElseThrow().score()).isLessThan(2);
        assertThat(events("order", onTrack.id(), BreachRiskScorer.DELAY_WARNING)).isEmpty();
    }

    /** A carrier exception on a parcel is a clear case too. */
    @Test
    void aCarrierExceptionRaisesTheScoreToLikely() {
        Order o = newOrder("web", "released", true);
        jdbc.sql("""
            insert into event(event_id, tenant_id, aggregate_type, aggregate_id, sequence, type, payload, actor)
            values (:id, :t, 'order', :o, 9999, 'order.carrier_exception', '{"condition":"delay"}'::jsonb, 'system:test')
            """).param("id", "evt-" + o.id()).param("t", TENANT).param("o", o.id()).update();
        OrderRisk r = scorer.scoreNow(o.id()).orElseThrow();
        assertThat(r.score()).isEqualTo(3);
        assertThat(r.reasons()).contains("carrier_exception_reported", "carrier reported an exception");
    }

    /**
     * B3-6: the learner learns from the score the gate acts on. Thirty orders the model was unsure about (confirm band, the
     * runtime's default 1 stored) arrive on time; six whose promise passed (L0 rule, 4) arrive late. Reading the stored
     * scores, a threshold of 2 warns exactly the late ones, so 2 is the earliest useful threshold. Reading the model's raw
     * unconfident answer instead would see every on-time order at that answer and push the threshold up.
     */
    @Test
    void theWarnEarlyLearnerLearnsFromTheScoreTheGateActedOn() {
        jdbc.sql("delete from learned_fact where tenant_id = :t and fact_key = 'WARN_EARLY_THRESHOLD'").param("t", TENANT).update();
        int unsureRaw = -1;
        for (int i = 0; i < 36; i++) {
            boolean late = i < 6;
            Order o = newOrder("web", "released", true);
            if (late) promiseDaysFromNow(o, -1); else backdateState(o.id(), Duration.ofHours(6));
            OrderRisk risk = scorer.scoreNow(o.id()).orElseThrow();
            var rec = decisionRecords.find(risk.decisionRecordId()).orElseThrow();
            if (!late) {
                assertThat(risk.score()).isEqualTo(1);
                unsureRaw = rec.value() instanceof Number n ? (int) Math.round(n.doubleValue()) : unsureRaw;
            }
            var ep = ai.intentoms.learning.model.Episode.open(ai.intentoms.platform.ids.Ids.prefixed("epi"), rec.id(), WarnEarlyThresholdLearner.DECISION, o.id(), null, null,
                rec.version(), "order", o.id(), rec.createdAt());
            episodes.upsertDecisionPoint(ep);
            String epId = episodes.findByDecisionId(rec.id()).orElseThrow().episodeId();
            outcomes.insert(ai.intentoms.learning.model.OutcomeRecord.of(ai.intentoms.platform.ids.Ids.prefixed("out"), epId,
                new ai.intentoms.learning.model.OutcomeData.DeliveryOutcome(!late, late ? 30 : 0, false, false), java.time.Instant.now(), ai.intentoms.platform.ids.Ids.id(), java.time.Instant.now()));
        }
        assertThat(unsureRaw).as("the model's own unsure answer differs from the stored score").isGreaterThan(1);
        assertThat(warnEarly.sweepOnce()).isEqualTo(1);
        var fact = factRepo.active(SubjectType.DECISION, WarnEarlyThresholdLearner.DECISION, FactKey.WARN_EARLY_THRESHOLD).orElseThrow();
        assertThat(((ai.intentoms.learning.model.TypedValue.NumberValue) fact.value()).value()).isEqualTo(2.0);
    }

    @Test
    void staticThresholdWorksWithoutLearning() {
        config.set(WarnEarlyThresholdLearner.STATIC_KEY, 0);
        Order o = newOrder("web", "released", true);
        scorer.scoreNow(o.id());
        Map<String, Object> payload = Json.readMap((String) events("order", o.id(), BreachRiskScorer.DELAY_WARNING).get(0).get("payload"));
        assertThat(payload).containsEntry("thresholdSource", "STATIC");
    }

    @Test
    void learnedBacklogBreachProbabilityReachesTheDecisionOnlyOncePromoted() {
        facts.list(SubjectType.NODE, "DC-EAST", FactKey.NODE_BACKLOG_BREACH_CURVE, List.of("0:0.6000", "100:0.9000"), 50, BacklogBreachCurveLearner.KEY);
        factCache.invalidateAll();

        Order before = newOrder("web", "released", true);
        var plain = scorer.scoreNow(before.id()).orElseThrow();
        assertThat(node(plain.decisionRecordId())).doesNotContainKey("breach_probability");

        parameters.promote(NodeReliabilityFeatures.BACKLOG_BREACH, BacklogBreachCurveLearner.KEY, 0.0, 1.0, null, null, "test");
        Order after = newOrder("web", "released", true);
        var learned = scorer.scoreNow(after.id()).orElseThrow();
        assertThat(node(learned.decisionRecordId())).containsEntry("breach_probability", 0.6);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> node(String recordId) {
        return (Map<String, Object>) decisionRecords.find(recordId).orElseThrow().input().get("node");
    }
}
