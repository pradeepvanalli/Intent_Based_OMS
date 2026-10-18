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
        facts.number(SubjectType.DECISION, WarnEarlyThresholdLearner.DECISION, FactKey.WARN_EARLY_THRESHOLD, 0, 40, WarnEarlyThresholdLearner.KEY, 0.5);
        factCache.invalidateAll();

        Order quiet = newOrder("web", "released", true);
        backdateState(quiet.id(), Duration.ofHours(6));
        scorer.scoreNow(quiet.id());
        assertThat(events("order", quiet.id(), BreachRiskScorer.DELAY_WARNING)).isEmpty();

        parameters.promote(WarnEarlyThresholdLearner.PARAMETER, WarnEarlyThresholdLearner.KEY, 0.0, 4.0, null, null, "test");
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
