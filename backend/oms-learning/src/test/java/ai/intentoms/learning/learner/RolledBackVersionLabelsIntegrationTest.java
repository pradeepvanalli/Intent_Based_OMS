package ai.intentoms.learning.learner;

import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.Hypothesis;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.store.HypothesisRepository;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.learning.support.DecisionFabricTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C4-N1 (audit 4, DF-02, DF-03, DF-04, DF-05, DF-07): learners that read human labels used to query only records of
 * the live definition version ({@code labelledBetween(name, def.version(), ...)}): 22 live resolutions on
 * fulfilment_intent v4 (promoted and then rolled back) vanished for the calibrator and provider routing once v4 left
 * live, dropping their sample size back to what it was before. A label is evidence about the situation the model
 * faced, not about the decision definition version that happened to be live when it was recorded, so a promotion or
 * rollback must not drop it. Fixed with one shared repository method, {@code labelledSince} (no version filter), used
 * by all five learners.
 *
 * <p>Scenario shared by every test here: a decision is published at v1, republished at v2, 20 identical-situation
 * human resolutions are recorded while v2 is live, then the decision is rolled back to v1 (v2 retired). Each learner
 * must still see all 20.
 */
class RolledBackVersionLabelsIntegrationTest extends DecisionFabricTestBase {
    @Autowired ConfidenceCalibrator calibrator;
    @Autowired ProviderRoutingLearner providerRouting;
    @Autowired GoldenSetGrowthLearner goldenGrowth;
    @Autowired OverrideToRuleLearner overrideToRule;
    @Autowired RuleUtilizationLearner ruleUtilization;
    @Autowired LearnedFactRepository facts;
    @Autowired HypothesisRepository hypotheses;

    /** v1 live -> v2 live (v1 retired) -> 20 identical-situation human resolutions recorded on v2 -> rolled back to v1. */
    private String setUpRolledBackLabels(String prefix) {
        String gold = prefix + "_gold_" + ai.intentoms.platform.ids.Ids.shortId().toLowerCase();
        String name = installProbe(prefix, "evaluation: { goldenSet: " + gold + ", minAccuracyInActBand: 0.0, maxCalibrationError: 1.0 }");
        DecisionDefinition v1 = live(name);
        DecisionDefinition draft2 = studio.createDraft(name, v1.yaml());
        studio.publish(name, draft2.version(), "live", true);
        assertThat(liveVersion(name)).isEqualTo(draft2.version());

        Instant at = Instant.now().minus(Duration.ofHours(1));
        for (int i = 0; i < 20; i++) {
            insertRecord(name, draft2.version(), at, "approve", 0.57, 20, Map.of(),
                Map.of("human_resolution", Map.of("value", "review", "outcome", "override", "resolvedBy", "user:ops")), "system:test");
        }

        studio.rollback(name);
        assertThat(liveVersion(name)).as("rolled back to v1").isEqualTo(v1.version());
        return name;
    }

    @Test
    void confidenceCalibratorStillCountsResolutionsFromTheRolledBackVersion() {
        String name = setUpRolledBackLabels("c4n1_calib");
        assertThat(calibrator.resolved(name)).as("n grows even though the records' version is no longer live").hasSize(20);
    }

    @Test
    void ruleUtilizationGroundTruthStillCountsResolutionsFromTheRolledBackVersion() {
        String name = setUpRolledBackLabels("c4n1_util");
        DecisionDefinition live = live(name);
        List<RuleUtilizationLearner.Truth> truth = ruleUtilization.groundTruth(live, Instant.now().minus(Duration.ofDays(30)));
        assertThat(truth).hasSize(20);
    }

    @Test
    void goldenSetGrowthRaisesAHypothesisFromResolutionsOnTheRolledBackVersion() {
        String name = setUpRolledBackLabels("c4n1_gold");
        assertThat(goldenGrowth.sweepOnce()).isPositive();
        Hypothesis h = hypotheses.byStatus("OPEN", 500).stream()
            .filter(x -> "add_golden_case".equals(x.lever().get("action")) && name.equals(x.lever().get("decision"))).findFirst().orElseThrow();
        assertThat(((Number) h.lever().get("resolutions")).intValue()).isEqualTo(20);
    }

    @Test
    void overrideToRuleRaisesAHypothesisFromResolutionsOnTheRolledBackVersion() {
        String name = setUpRolledBackLabels("c4n1_rule");
        assertThat(overrideToRule.sweepOnce()).isPositive();
        Hypothesis h = hypotheses.byStatus("OPEN", 500).stream()
            .filter(x -> "add_rule".equals(x.lever().get("action")) && name.equals(x.lever().get("decision"))).findFirst().orElseThrow();
        assertThat(((Number) h.lever().get("resolutions")).intValue()).isEqualTo(20);
    }

    @Test
    void providerRoutingStillCountsLabelledAccuracyFromTheRolledBackVersion() {
        String name = setUpRolledBackLabels("c4n1_prov");
        providerRouting.sweepOnce();
        var fact = facts.active(SubjectType.DECISION, name + ":heuristic", FactKey.PROVIDER_ACCURACY).orElseThrow(
            () -> new AssertionError("expected a PROVIDER_ACCURACY fact for " + name + ":heuristic"));
        assertThat(fact.sampleSize()).isEqualTo(20);
    }
}
