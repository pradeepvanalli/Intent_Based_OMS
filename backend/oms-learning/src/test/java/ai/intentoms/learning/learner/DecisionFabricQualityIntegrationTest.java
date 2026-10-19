package ai.intentoms.learning.learner;

import ai.intentoms.decision.definition.GoldenCaseRepository;
import ai.intentoms.decision.model.Answer;
import ai.intentoms.decision.model.Band;
import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.model.GoldenCase;
import ai.intentoms.decision.model.Layer;
import ai.intentoms.decision.model.Mode;
import ai.intentoms.decision.model.Tier;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.Hypothesis;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.HypothesisRepository;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.learning.support.DecisionFabricTestBase;
import ai.intentoms.platform.ids.Ids;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DF-01 escalations counted per tier, DF-03 golden candidates from normalised situations with agreement, dedupe and
 * no contradiction with a live rule, DF-04 agreement over every resolution (defect 22), DF-05 every provider compared on
 * accuracy, cost and latency, through the real Spring context and database.
 */
class DecisionFabricQualityIntegrationTest extends DecisionFabricTestBase {
    @Autowired BandThresholdLearner bandThreshold;
    @Autowired GoldenSetGrowthLearner goldenGrowth;
    @Autowired OverrideToRuleLearner overrideToRule;
    @Autowired ProviderRoutingLearner providerRouting;
    @Autowired GoldenCaseRepository golden;
    @Autowired HypothesisRepository hypotheses;
    @Autowired LearnedFactRepository facts;

    private static String evaluation(String goldSet) {
        return "evaluation: { goldenSet: " + goldSet + ", minAccuracyInActBand: 0.5, maxCalibrationError: 1.0 }";
    }

    /** A live probe decision of the given tier with a golden set the model gets right (as the DF-01 test does). */
    private String probeWithGolden(String prefix, String tier) {
        String name = installProbe(prefix, null);
        String gold = name + "_gold";
        var draft = studio.createDraft(name, probeYaml(name, evaluation(gold)).replace("tier: T1", "tier: " + tier));
        for (int i = 0; i < 12; i++) {
            Map<String, Object> input = order("g" + i, 100 + i);
            DecisionResult sim = decisions.decide(name, input, DecisionOptions.simulation("golden_case", "g" + i, draft.version()));
            golden.upsert(new GoldenCase(gold, "case_" + i, input, Map.of("value", sim.value()), "model answer"));
        }
        studio.publish(name, draft.version(), "live", true);
        return name;
    }

    private Proposal openFor(String target) {
        return proposals.byStatus("DRAFT", 500).stream().filter(x -> x.changeset().target().toString().equals(target)).findFirst().orElseThrow();
    }

    /** DF-01: the same traffic is 0 tasks a day for T1 (its confirm band applies the default) and 2 a day for T2. */
    @Test
    void escalationBudgetCountsHumanTasksTheWayEachTierCreatesThem() {
        List<Double> confirmBand = java.util.Collections.nCopies(28, 0.6);
        assertThat(BandThresholdLearner.humanTasks(Tier.T0, 0.5, 0.8, confirmBand)).isEqualTo(28);   // T0 confirm band goes to a person
        assertThat(BandThresholdLearner.humanTasks(Tier.T0, 0.5, 0.8, List.of(0.2, 0.3))).isZero();   // T0 low confidence acts with the escape value
        assertThat(BandThresholdLearner.humanTasks(Tier.T1, 0.5, 0.8, confirmBand)).isZero();
        assertThat(BandThresholdLearner.humanTasks(Tier.T1, 0.5, 0.8, List.of(0.2, 0.3, 0.7))).isEqualTo(2);
        assertThat(BandThresholdLearner.humanTasks(Tier.T2, 0.5, 0.8, confirmBand)).isEqualTo(28);
        assertThat(BandThresholdLearner.humanTasks(Tier.T3, 0.5, 0.8, List.of(0.99))).isEqualTo(1);

        String t1 = probeWithGolden("e4_df01a", "T1");
        String t2 = probeWithGolden("e4_df01b", "T2");
        for (String name : List.of(t1, t2)) {
            int v = liveVersion(name);
            for (int i = 0; i < 28; i++) insertRecord(name, v, Instant.now().minus(Duration.ofDays(1)), "approve", 0.6, 20, Map.of(), Map.of(), "system:test");
        }
        bandThreshold.sweepOnce();
        assertThat(openFor("decision:" + t1).changeset().motivatedBy()).contains("about 0.0 escalations/day at the new threshold vs 0.0 now");
        assertThat(openFor("decision:" + t2).changeset().motivatedBy()).contains("about 2.0 escalations/day at the new threshold vs 2.0 now");
    }

    private void resolve(DecisionResult r, String action) {
        records.label(r.recordId(), "human_resolution", Map.of("action", action, "reasonCode", "test", "resolvedBy", "user:ops"));
    }

    /** DF-03: grouping by situation (not order id), 90% agreement, nothing the golden set has, nothing a live rule contradicts. */
    @Test
    void goldenCandidatesComeFromAgreedNewSituationsThatNoLiveRuleContradicts() {
        String name = installProbe("e4_df03", null);
        String gold = name + "_gold";
        var v = studio.createDraft(name, probeYaml(name, evaluation(gold)));
        studio.publish(name, v.version(), "live", true);
        golden.upsert(new GoldenCase(gold, "existing", order("gx", 500), Map.of("value", "review"), "already covered"));

        // Three different orders of about 100 (one situation once ids are dropped): a candidate.
        for (double value : new double[]{101, 102, 104}) resolve(decideLive(name, "ord_" + Ids.shortId(), value, null), "approve");
        // About 300: people disagree (3 of 4): not a candidate.
        for (String a : List.of("review", "review", "review", "approve")) resolve(decideLive(name, "ord_" + Ids.shortId(), 300, null), a);
        // About 500: the golden set already has it.
        for (int i = 0; i < 3; i++) resolve(decideLive(name, "ord_" + Ids.shortId(), 500, null), "review");
        // About 2000: the live rule big_order answers "review"; people answering "approve" would contradict it.
        for (int i = 0; i < 3; i++) resolve(decideLive(name, "ord_" + Ids.shortId(), 2000, null), "approve");
        // An open candidate that came from elsewhere and contradicts the rule is rejected on the next sweep.
        DecisionResult seeded = decideLive(name, "ord_" + Ids.shortId(), 2500, null);
        Hypothesis contradicting = new Hypothesis(Ids.prefixed("hyp"), Map.of("decision", name), "golden_set_coverage", ai.intentoms.learning.model.Direction.INCREASE,
            Map.of("action", "add_golden_case", "decision", name, "groupKey", name + ":seeded", "expectedValue", "approve", "sourceRecordIds", List.of(seeded.recordId())),
            0.7, List.of(seeded.recordId()), "seeded", Hypothesis.Status.OPEN, Instant.now());
        hypotheses.insert(contradicting);

        goldenGrowth.sweepOnce();

        List<Hypothesis> mine = hypotheses.byStatus("OPEN", 500).stream()
            .filter(h -> "add_golden_case".equals(h.lever().get("action")) && name.equals(h.lever().get("decision"))).toList();
        assertThat(mine).singleElement().satisfies(h -> {
            assertThat(h.lever()).containsEntry("expectedValue", "approve").containsEntry("resolutions", 3).containsEntry("agreement", 1.0);
            assertThat(h.evidence()).hasSize(3);
        });
        assertThat(hypotheses.find(contradicting.hypothesisId()).orElseThrow().status()).isEqualTo(Hypothesis.Status.REJECTED);
    }

    private DecisionRecord craft(String decision, int version, double value, String modelValue, String provider, double cost, long latencyMs, String humanAction) {
        String id = Ids.prefixed("dr");
        String subject = "ord_" + Ids.shortId();
        Map<String, Object> labels = humanAction == null ? Map.of() : Map.of("human_resolution", Map.of("action", humanAction, "reasonCode", "test", "resolvedBy", "user:ops"));
        DecisionRecord r = new DecisionRecord(id, decision, version, Mode.LIVE, "order", subject, subject, Layer.L1_SYSTEM_ONE, Band.CONFIRM, "confirm", modelValue,
            Map.<String, Answer>of(), 0.7, provider, "test", "hash_" + id, Map.of(), order(subject, value), List.of(), List.of(), latencyMs, cost, 0, true, null, "system:test",
            Map.of(), null, null, null, null, labels, Instant.now().minus(Duration.ofHours(2)), Map.of(), List.of("approve", "review", "unclear"), modelValue, 1.0, version);
        records.insert(r);
        return r;
    }

    /** DF-04 / defect 22: agreement counts people who confirmed the model too, and the hypothesis carries a condition. */
    @Test
    void overrideToRuleMeasuresAgreementOverEveryResolution() {
        String name = installProbe("e4_df04");
        int v = liveVersion(name);
        // About 100: 19 people override "approve" with "review", 1 confirms: 95% agree, mostly overrides -> a rule.
        for (int i = 0; i < 20; i++) craft(name, v, 100, "approve", "heuristic", 0, 10, i < 19 ? "review" : "approve");
        // About 300: 20 overrides to "review" but 10 people confirmed "approve": 67% agreement -> no rule (only-overrides
        // counting would have said 100%).
        for (int i = 0; i < 30; i++) craft(name, v, 300, "approve", "heuristic", 0, 10, i < 20 ? "review" : "approve");

        overrideToRule.sweepOnce();

        List<Hypothesis> rules = hypotheses.byStatus("OPEN", 500).stream()
            .filter(h -> "add_rule".equals(h.lever().get("action")) && name.equals(h.lever().get("decision"))).toList();
        assertThat(rules).singleElement().satisfies(h -> {
            assertThat(h.lever()).containsEntry("suggestedThen", "review").containsEntry("resolutions", 20).containsEntry("overrides", 19).containsEntry("agreement", 0.95);
            assertThat(String.valueOf(h.lever().get("when"))).contains("input.order.value >= 100 && input.order.value <= 100").contains("input.order.channel == 'web'");
        });
    }

    /** DF-05: every provider that answered is compared; the cheapest that meets the target wins, latency breaking near-ties. */
    @Test
    void providerRoutingComparesEveryProviderOnAccuracyCostAndLatency() {
        String name = "e4_df05_" + Ids.shortId().toLowerCase();
        var d = studio.createDraft(name, probeYaml(name, null).replace("provider: heuristic", "provider: jev\nfallbackProvider: heuristic"));
        studio.publish(name, d.version(), "live", true);
        int v = d.version();
        // provider, cost per call, latency ms, kept out of 25 labelled
        Object[][] providers = {{"jev", 0.004, 800L, 24}, {"openrouter:small", 0.0010, 300L, 24}, {"openrouter:fast", 0.00105, 120L, 24}, {"openrouter:tiny", 0.0002, 90L, 17}};
        for (Object[] p : providers) {
            for (int i = 0; i < 25; i++) craft(name, v, 100, "approve", (String) p[0], (double) p[1], (long) p[2], i < (int) p[3] ? "approve" : "review");
        }

        providerRouting.sweepOnce();

        Proposal p = openFor("config:" + ai.intentoms.learning.hooks.PromotedProviderRouter.KEY_PREFIX + name);
        PolicyChange.ParameterChange change = (PolicyChange.ParameterChange) p.changeset().changes().get(0);
        assertThat(change.from()).isEqualTo("jev");
        assertThat(change.to()).isEqualTo("openrouter:fast"); // as cheap as "small" within 10%, faster; "tiny" is cheaper but misses the target
        assertThat(p.changeset().motivatedBy()).contains("openrouter:tiny").contains("openrouter:small").contains("p95 120 ms");
        assertThat(((TypedValue.NumberValue) facts.active(SubjectType.DECISION, name + ":openrouter:fast", FactKey.PROVIDER_LATENCY_MS).orElseThrow().value()).value()).isEqualTo(120.0);
        assertThat(((TypedValue.NumberValue) facts.active(SubjectType.DECISION, name + ":openrouter:tiny", FactKey.PROVIDER_ACCURACY).orElseThrow().value()).value()).isEqualTo(0.68);
    }
}
