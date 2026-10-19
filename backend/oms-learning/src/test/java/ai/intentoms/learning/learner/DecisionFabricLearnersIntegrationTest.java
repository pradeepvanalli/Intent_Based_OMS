package ai.intentoms.learning.learner;

import ai.intentoms.decision.definition.GoldenCaseRepository;
import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.model.GoldenCase;
import ai.intentoms.learning.api.GoldenCandidateController;
import ai.intentoms.learning.model.Hypothesis;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.store.HypothesisRepository;
import ai.intentoms.learning.support.DecisionFabricTestBase;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.ids.Ids;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** DF-01 (threshold within an escalation budget), DF-03 (golden-case candidate to signed-off case), DF-07 (rule retirement). */
class DecisionFabricLearnersIntegrationTest extends DecisionFabricTestBase {
    @Autowired BandThresholdLearner bandThreshold;
    @Autowired GoldenSetGrowthLearner goldenGrowth;
    @Autowired RuleUtilizationLearner ruleUtilization;
    @Autowired GoldenCandidateController goldenCandidates;
    @Autowired GoldenCaseRepository golden;
    @Autowired HypothesisRepository hypotheses;
    @Autowired TenantConfigStore config;

    private static String evaluation(String name) {
        return "evaluation: { goldenSet: " + name + "_gold, minAccuracyInActBand: 0.5, maxCalibrationError: 1.0 }";
    }

    @Test
    void bandThresholdProposesAMoveWithinTheEscalationBudget() {
        String prefix = "f4_df01";
        String name = installProbe(prefix, null);
        // Re-install with a golden set, seeded with cases whose expected answer is what the model gives (accuracy 100%).
        String gold = prefix + "_" + Ids.shortId().toLowerCase();
        var draft = studio.createDraft(name, probeYaml(name, evaluation(gold)));
        for (int i = 0; i < 12; i++) {
            Map<String, Object> input = order("g" + i, 100 + i);
            DecisionResult sim = decisions.decide(name, input, DecisionOptions.simulation("golden_case", "g" + i, draft.version()));
            golden.upsert(new GoldenCase(gold + "_gold", "case_" + i, input, Map.of("value", sim.value()), "model answer"));
        }
        studio.publish(name, draft.version(), "live", true);
        for (int i = 0; i < 10; i++) decideLive(name, "ord_b" + i + Ids.shortId(), 100, null);
        config.set(BandThresholdLearner.BUDGET_KEY, 100);
        try {
            bandThreshold.sweepOnce();
        } finally {
            config.delete(BandThresholdLearner.BUDGET_KEY);
        }
        Proposal p = proposals.byStatus("DRAFT", 200).stream().filter(x -> x.changeset().target().toString().equals("decision:" + name)).findFirst().orElseThrow();
        assertThat(p.changeset().changes()).singleElement().isInstanceOf(PolicyChange.ThresholdChange.class);
        PolicyChange.ThresholdChange tc = (PolicyChange.ThresholdChange) p.changeset().changes().get(0);
        assertThat(tc.from()).isEqualTo(0.8);
        assertThat(tc.to()).isEqualTo(0.75); // capped at the 0.05 max step toward the 0.5 bin
        assertThat(p.changeset().motivatedBy()).contains("escalations/day").contains("budget 100/day");
    }

    @Test
    void recurringHumanResolutionsBecomeAGoldenCaseOnlyAfterSignOff() {
        String name = installProbe("f4_df03", null);
        String gold = "f4_df03_" + Ids.shortId().toLowerCase();
        var v = studio.createDraft(name, probeYaml(name, evaluation(gold)));
        studio.publish(name, v.version(), "live", true);
        for (int i = 0; i < 3; i++) {
            DecisionResult r = decideLive(name, "ord_same", 250, null);
            records.label(r.recordId(), "human_resolution", Map.of("action", "review", "by", "user:ops"));
        }

        goldenGrowth.sweepOnce();
        Map<String, Object> candidate = goldenCandidates.candidates().stream().filter(c -> name.equals(c.get("decision"))).findFirst().orElseThrow();
        assertThat(candidate.get("expectedValue")).isEqualTo("review");
        assertThat(golden.forSet(gold + "_gold")).isEmpty(); // nothing written before a reviewer signs off

        String hypothesisId = (String) candidate.get("hypothesisId");
        // GV-04: the hypothesis is attributed to the learner that raised it, not a generic system actor.
        assertThat(ledger(hypothesisId, "hypothesis.raised")).singleElement().satisfies(e -> assertThat(e.get("actor")).isEqualTo("system:golden_set_growth"));
        assertThat(hypotheses.raisedBy(hypothesisId)).contains("system:golden_set_growth");

        GoldenCase gc = goldenCandidates.signOff(hypothesisId, new GoldenCandidateController.SignOff(null, "matches policy"));
        assertThat(gc.expectedValue()).isEqualTo("review");
        assertThat(golden.forSet(gold + "_gold")).extracting(GoldenCase::caseId).contains(gc.caseId());
        assertThat(hypotheses.find(hypothesisId).orElseThrow().status()).as("C-N3: a signed-off candidate is done, not left DRAFTED").isEqualTo(Hypothesis.Status.ACCEPTED);
        // The sign-off is its own ledger event by the reviewer, with an audit row.
        assertThat(ledger(hypothesisId, "golden_case.signed_off")).singleElement().satisfies(e -> assertThat(String.valueOf(e.get("actor"))).startsWith("user:"));
        assertThat(jdbc.sql("select count(*) from audit_log where action = 'learning.golden_case.signed_off' and object_id = :id").param("id", hypothesisId)
            .query(Long.class).single()).isEqualTo(1L);
    }

    private java.util.List<Map<String, Object>> ledger(String aggregateId, String type) {
        return jdbc.sql("select type, actor, payload::text as payload from event where aggregate_type = 'learning' and aggregate_id = :id and type = :type order by sequence")
            .param("id", aggregateId).param("type", type).query().listOfRows();
    }

    @Test
    void rulesThatNeverFireAreProposedForRetirement() {
        String name = installProbe("f4_df07a");
        for (int i = 0; i < 6; i++) decideLive(name, "ord_n" + i + Ids.shortId(), 100, null);
        ruleUtilization.sweepOnce();
        Proposal p = openFor(name);
        assertThat(p.changeset().changes()).allMatch(c -> c instanceof PolicyChange.RuleRetire)
            .extracting(c -> ((PolicyChange.RuleRetire) c).ruleId()).contains("never_fires");
    }

    @Test
    void aRuleTheModelBeatsOnReplayIsProposedForRetirementWithEvidence() {
        String name = "f4_df07b_" + Ids.shortId().toLowerCase();
        String yaml = probeYaml(name, null).replaceAll("(?s)  - id: never_fires.*?priority: 20\\n", "");
        var d = studio.createDraft(name, yaml);
        studio.publish(name, d.version(), "live", true);
        decideLive(name, "ord_small" + Ids.shortId(), 100, null);
        for (int i = 0; i < 4; i++) {
            DecisionResult r = decideLive(name, "ord_big" + i + Ids.shortId(), 2000, null);
            assertThat(r.rulesFired()).contains("big_order");
            records.label(r.recordId(), "human_resolution", Map.of("action", "unclear", "by", "user:ops"));
        }
        ruleUtilization.sweepOnce();
        Proposal p = openFor(name);
        PolicyChange.RuleRetire rr = (PolicyChange.RuleRetire) p.changeset().changes().get(0);
        assertThat(rr.ruleId()).isEqualTo("big_order");
        assertThat(p.changeset().motivatedBy()).contains("the model agrees with people on 100%").contains("the rule on 0%");
    }

    private Proposal openFor(String name) {
        List<Proposal> drafts = proposals.byStatus("DRAFT", 500);
        return drafts.stream().filter(x -> x.changeset().target().toString().equals("decision:" + name)).findFirst().orElseThrow();
    }
}
