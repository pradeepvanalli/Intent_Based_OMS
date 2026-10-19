package ai.intentoms.learning.learner;

import ai.intentoms.decision.model.Answer;
import ai.intentoms.decision.model.Band;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.Layer;
import ai.intentoms.decision.model.Mode;
import ai.intentoms.learning.api.GoldenCandidateController;
import ai.intentoms.learning.model.Hypothesis;
import ai.intentoms.learning.store.HypothesisRepository;
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
 * DF-04 (audit C-N3): override-to-rule is no longer starved by DF-03, and the rule it drafts generalises.
 * <ul>
 *   <li>People resolving one situation the same way first make it a golden-case candidate (at 3); a reviewer signs it
 *       off. That candidate used to stay DRAFTED with the same group key, so 23 identical overrides never became a rule.</li>
 *   <li>Two situations people override to the same answer merge into one rule on what they share, as long as every
 *       person-resolved case the merged condition matches still agrees; a situation people answer differently stays out.</li>
 *   <li>Agents' completions (E3-N1) are not human judgement: twenty of them draft nothing.</li>
 * </ul>
 */
class OverrideToRuleStarvationIntegrationTest extends DecisionFabricTestBase {
    @Autowired GoldenSetGrowthLearner goldenGrowth;
    @Autowired OverrideToRuleLearner overrideToRule;
    @Autowired GoldenCandidateController goldenCandidates;
    @Autowired HypothesisRepository hypotheses;

    /** A live-shaped escalated record a person (or an agent) resolved: L3_HUMAN, the model's answer kept, the label HumanResolutionCapture writes. */
    private DecisionRecord resolved(String decision, int version, String channel, double value, String modelValue, String chosen, String by) {
        String id = Ids.prefixed("dr");
        String subject = "ord_" + Ids.shortId();
        Map<String, Object> label = Map.of("value", chosen, "action", chosen, "outcome", chosen.equals(modelValue) ? "accept" : "override", "reasonCode", "test",
            "resolvedBy", by, "resolvedByType", ai.intentoms.learning.model.HumanResolution.resolverType(by), "recommendedValue", modelValue);
        DecisionRecord r = new DecisionRecord(id, decision, version, Mode.LIVE, "order", subject, subject, Layer.L3_HUMAN, Band.ESCALATE, "escalate", modelValue,
            Map.<String, Answer>of(), 0.4, "heuristic", "test", "hash_" + id, Map.of(), Map.of("order", Map.of("id", subject, "channel", channel, "value", value)), List.of(),
            List.of("low_confidence"), 10, 0, 0, true, null, "system:test", Map.of("escalationReason", "low_confidence", "modelAnswer", modelValue), null, null, null, null,
            Map.of("human_resolution", label), Instant.now().minus(Duration.ofHours(3)), Map.of(), List.of("approve", "review", "unclear"), "review", 1.0, version);
        records.insert(r);
        return r;
    }

    private List<Hypothesis> rulesFor(String name) {
        return java.util.stream.Stream.of("OPEN", "DRAFTED").flatMap(s -> hypotheses.byStatus(s, 500).stream())
            .filter(h -> "add_rule".equals(h.lever().get("action")) && name.equals(h.lever().get("decision"))).toList();
    }

    @Test
    void aSignedOffGoldenCandidateForTheSameSituationNoLongerBlocksTheRule() {
        String name = installProbe("gc_df04a");
        String gold = name + "_g";
        var draft = studio.createDraft(name, probeYaml(name, "evaluation: { goldenSet: " + gold + ", minAccuracyInActBand: 0.0, maxCalibrationError: 1.0 }"));
        studio.publish(name, draft.version(), "live", true);
        int v = liveVersion(name);
        // The audit's repro: 23 identical cases, every one overridden to the same answer.
        for (int i = 0; i < 23; i++) resolved(name, v, "web", 250, "approve", "review", "user:ops");

        goldenGrowth.sweepOnce();
        Hypothesis candidate = hypotheses.byStatus("OPEN", 500).stream()
            .filter(h -> "add_golden_case".equals(h.lever().get("action")) && name.equals(h.lever().get("decision"))).findFirst().orElseThrow();
        assertThat(String.valueOf(candidate.lever().get("groupKey"))).startsWith("golden:");
        goldenCandidates.signOff(candidate.hypothesisId(), new GoldenCandidateController.SignOff(null, "agreed"));
        assertThat(hypotheses.find(candidate.hypothesisId()).orElseThrow().status()).as("signed off is done, not DRAFTED").isEqualTo(Hypothesis.Status.ACCEPTED);

        overrideToRule.sweepOnce();
        assertThat(rulesFor(name)).singleElement().satisfies(h -> {
            assertThat(h.lever()).containsEntry("suggestedThen", "review").containsEntry("resolutions", 23).containsEntry("overrides", 23);
            assertThat(String.valueOf(h.lever().get("groupKey"))).startsWith("rule:").isNotEqualTo(candidate.lever().get("groupKey"));
            // Identical cases support nothing more general than what they all share.
            assertThat(String.valueOf(h.lever().get("when"))).contains("input.order.channel == 'web'").contains("input.order.value >= 250 && input.order.value <= 250");
        });
        overrideToRule.sweepOnce();
        assertThat(rulesFor(name)).as("one candidate per rule").hasSize(1);
    }

    @Test
    void situationsOverriddenTheSameWayMergeIntoTheRuleTheirSharedFieldsDescribe() {
        String name = installProbe("gc_df04b");
        int v = liveVersion(name);
        // Two web situations of 12 each (each below the 20 minimum on its own), both overridden to "review".
        for (int i = 0; i < 12; i++) resolved(name, v, "web", 150, "approve", "review", "user:ops");
        for (int i = 0; i < 12; i++) resolved(name, v, "web", 180, "approve", "review", i % 2 == 0 ? "user:risk" : "user:ops");
        // Marketplace orders of the same value people approve: the rule must not cover them.
        for (int i = 0; i < 6; i++) resolved(name, v, "marketplace", 150, "review", "approve", "user:ops");
        // A web order far above people answer differently: outside the merged range, it stays out.
        for (int i = 0; i < 4; i++) resolved(name, v, "web", 900, "review", "approve", "user:ops");

        overrideToRule.sweepOnce();
        assertThat(rulesFor(name)).singleElement().satisfies(h -> {
            assertThat(h.lever()).containsEntry("suggestedThen", "review").containsEntry("resolutions", 24).containsEntry("situations", 2);
            String when = String.valueOf(h.lever().get("when"));
            assertThat(when).contains("input.order.channel == 'web'").contains("input.order.value >= 150 && input.order.value <= 180");
        });
    }

    /** The live repro: one customer's identical notes, a learned fact that moved half way, and other web orders people answered differently. */
    @Test
    void aSharedNoteKeepsTheMergedRuleSpecificEnoughToStayConsistent() {
        String name = "gc_df04d_" + Ids.shortId().toLowerCase();
        var d = studio.createDraft(name, probeYaml(name, null).replace("    - order.value\n", "    - order.value\n    - order.notes\n"));
        studio.publish(name, d.version(), "live", true);
        int v = d.version();
        for (int i = 0; i < 13; i++) noted(name, v, 150, List.of("leave at the side door please"), "approve", "review");
        for (int i = 0; i < 7; i++) noted(name, v, 160, List.of("leave at the side door please"), "approve", "review");
        for (int i = 0; i < 4; i++) noted(name, v, 155, List.of(), "approve", "approve");
        overrideToRule.sweepOnce();
        assertThat(rulesFor(name)).singleElement().satisfies(h -> {
            assertThat(h.lever()).containsEntry("resolutions", 20).containsEntry("situations", 2);
            assertThat(String.valueOf(h.lever().get("when"))).contains("size(input.order.notes) == 1").contains("'leave at the side door please' in input.order.notes")
                .contains("input.order.value >= 150 && input.order.value <= 160");
        });
    }

    private void noted(String decision, int version, double value, List<String> notes, String modelValue, String chosen) {
        String id = Ids.prefixed("dr");
        String subject = "ord_" + Ids.shortId();
        Map<String, Object> label = Map.of("value", chosen, "outcome", chosen.equals(modelValue) ? "accept" : "override", "resolvedBy", "user:ops", "resolvedByType", "person");
        records.insert(new DecisionRecord(id, decision, version, Mode.LIVE, "order", subject, subject, Layer.L3_HUMAN, Band.ESCALATE, "escalate", modelValue,
            Map.<String, Answer>of(), 0.4, "heuristic", "test", "hash_" + id, Map.of(), Map.of("order", Map.of("id", subject, "channel", "web", "value", value, "notes", notes)),
            List.of(), List.of(), 10, 0, 0, true, null, "system:test", Map.of(), null, null, null, null, Map.of("human_resolution", label), Instant.now().minus(Duration.ofHours(2)),
            Map.of(), List.of("approve", "review", "unclear"), "review", 1.0, version));
    }

    @Test
    void agentsResolvingTheSameWayTwentyTimesDraftNoRule() {
        String name = installProbe("gc_df04c");
        int v = liveVersion(name);
        for (int i = 0; i < 22; i++) resolved(name, v, "web", 320, "approve", "review", "agent:ops-copilot");
        overrideToRule.sweepOnce();
        assertThat(rulesFor(name)).as("agent completions are not human judgement").isEmpty();
        for (int i = 0; i < 20; i++) resolved(name, v, "web", 320, "approve", "review", "user:ops");
        overrideToRule.sweepOnce();
        assertThat(rulesFor(name)).singleElement().satisfies(h -> assertThat(h.lever()).containsEntry("resolutions", 20));
    }
}
