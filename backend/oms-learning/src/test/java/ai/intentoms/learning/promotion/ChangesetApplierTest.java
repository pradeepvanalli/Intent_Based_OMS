package ai.intentoms.learning.promotion;

import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.json.Json;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChangesetApplierTest {
    static final String YAML = """
        decision: d
        version: 3
        tier: T1
        provider: jev
        default: review
        questions:
          action:
            type: choice
            instructions: pick
            criteria: { approve: ok, review: check, unclear: unsure }
        composite:
          outputKey: score
          weights: { a: 0.5, b: 0.5 }
        rules:
          - { id: r1, when: "input.x > 1", then: review, priority: 10 }
          - { id: r2, when: "input.x < 0", then: review, priority: 20 }
        bands:
          act: { confidence_gte: 0.8 }
          confirm: { confidence_gte: 0.5 }
          escalate: { otherwise: true }
        """;

    @Test @SuppressWarnings("unchecked")
    void everyDecisionChangeTypeLandsInTheYaml() {
        String out = ChangesetApplier.applyToDecision(YAML, List.of(
            new PolicyChange.ThresholdChange("d", "act", 0.8, 0.85),
            new PolicyChange.WeightChange("d", "a", 0.5, 0.6),
            new PolicyChange.RuleRetire("d", "r1", "unused"),
            new PolicyChange.RuleAdd("d", "input.channel == 'b2b'", "approve", "b2b_fast_lane"),
            new PolicyChange.ParameterChange("provider", "jev", "heuristic")));
        Map<String, Object> m = Json.yamlMap(out);
        assertThat(((Map<String, Object>) ((Map<String, Object>) m.get("bands")).get("act")).get("confidence_gte")).isEqualTo(0.85);
        assertThat(((Map<String, Object>) ((Map<String, Object>) m.get("composite")).get("weights")).get("a")).isEqualTo(0.6);
        assertThat(m.get("provider")).isEqualTo("heuristic");
        List<Map<String, Object>> rules = (List<Map<String, Object>>) m.get("rules");
        assertThat(rules).extracting(r -> r.get("id")).doesNotContain("r1").contains("r2");
        assertThat(rules).anyMatch(r -> "input.channel == 'b2b'".equals(r.get("when")) && "approve".equals(r.get("then")));
    }

    @Test
    void retiringAMissingRuleOrMixingInConfigChangesIsRefused() {
        assertThatThrownBy(() -> ChangesetApplier.applyToDecision(YAML, List.of(new PolicyChange.RuleRetire("d", "nope", null)))).isInstanceOf(ValidationException.class);
        assertThat(ChangesetApplier.decisionApplicable(List.of(new PolicyChange.AllowListChange("l", "e", true)))).isFalse();
        assertThat(ChangesetApplier.decisionApplicable(List.of(new PolicyChange.ParameterChange("sourcing.split_penalty", 1, 2)))).isFalse();
    }

    @Test @SuppressWarnings("unchecked")
    void configWeightChangeRescalesTheOthersAndRejectsAStaleFromValue() {
        Map<String, Object> w = (Map<String, Object>) ChangesetApplier.applyToConfig("sourcing.weights", Map.of("cost", 0.5, "speed", 0.3, "reliability", 0.2),
            new PolicyChange.WeightChange("sourcing.weights", "cost", 0.5, 0.3));
        assertThat(w.values().stream().mapToDouble(v -> ((Number) v).doubleValue()).sum()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(((Number) w.get("speed")).doubleValue()).isCloseTo(0.42, org.assertj.core.data.Offset.offset(1e-6));
        assertThatThrownBy(() -> ChangesetApplier.applyToConfig("sourcing.weights", Map.of("cost", 0.45), new PolicyChange.WeightChange("sourcing.weights", "cost", 0.5, 0.3)))
            .isInstanceOf(ValidationException.class);
    }

    // ---------------------------------------------------------------- defect 6: every weight change of a proposal at once

    private static final String W = "sourcing.weights";
    private static final Map<String, Object> STORED = Map.of("cost", 0.4, "time", 0.25, "soft", 0.25, "split", 0.1);
    private static final Map<String, Object> DEFAULTS = Map.of("cost", 0.4, "time", 0.3, "soft", 0.2, "split", 0.1);

    private static PolicyChange.WeightChange wc(String k, double from, double to) { return new PolicyChange.WeightChange(W, k, from, to); }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> applyAll(Object current, Object defaults, PolicyChange... changes) {
        return (Map<String, Object>) ChangesetApplier.applyAllToConfig(W, current, List.of(changes), defaults);
    }

    private static double d(Map<String, Object> m, String k) { return ((Number) m.get(k)).doubleValue(); }

    private static double sum(Map<String, Object> m) { return m.values().stream().mapToDouble(v -> ((Number) v).doubleValue()).sum(); }

    @Test
    void twoWeightChangesLandExactlyAndTheRestKeepTheirShare() {
        Map<String, Object> w = applyAll(STORED, DEFAULTS, wc("cost", 0.4, 0.45), wc("time", 0.25, 0.2));
        assertThat(d(w, "cost")).isEqualTo(0.45);
        assertThat(d(w, "time")).isEqualTo(0.2);
        // soft and split shared 0.35 at 25:10 and still do
        assertThat(d(w, "soft")).isCloseTo(0.25, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(d(w, "split")).isCloseTo(0.1, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(sum(w)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
    }

    @Test
    void threeWeightChangesAsTheWeightLearnerDraftsThem() {
        Map<String, Object> w = applyAll(STORED, DEFAULTS, wc("cost", 0.4, 0.43), wc("time", 0.25, 0.22), wc("soft", 0.25, 0.25));
        assertThat(w).containsEntry("cost", 0.43).containsEntry("time", 0.22).containsEntry("soft", 0.25);
        assertThat(d(w, "split")).isCloseTo(0.1, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void fourWeightChangesNamingEveryWeightAreNormalised() {
        Map<String, Object> w = applyAll(STORED, DEFAULTS, wc("cost", 0.4, 0.35), wc("time", 0.25, 0.3), wc("soft", 0.25, 0.25), wc("split", 0.1, 0.11));
        assertThat(sum(w)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
        assertThat(d(w, "cost")).isCloseTo(0.35 / 1.01, org.assertj.core.data.Offset.offset(1e-6));
        assertThatThrownBy(() -> applyAll(STORED, DEFAULTS, wc("cost", 0.4, 0.6), wc("time", 0.25, 0.3), wc("soft", 0.25, 0.25), wc("split", 0.1, 0.1)))
            .isInstanceOf(ValidationException.class).hasMessageContaining("instead of 1");
    }

    @Test
    void withNoStoredMapTheDefaultsSupplyTheOtherWeights() {
        Map<String, Object> w = applyAll(null, DEFAULTS, wc("cost", 0.4, 0.45), wc("time", 0.3, 0.25));
        assertThat(w.keySet()).containsExactlyInAnyOrder("cost", "time", "soft", "split");
        assertThat(w).containsEntry("cost", 0.45).containsEntry("time", 0.25);
        assertThat(sum(w)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
        // A from that does not match the default is a stale proposal.
        assertThatThrownBy(() -> applyAll(null, DEFAULTS, wc("cost", 0.5, 0.45))).isInstanceOf(ValidationException.class).hasMessageContaining("re-draft");
    }

    @Test
    void withNoStoredMapAndNoDefaultsEveryWeightMustBeNamed() {
        assertThatThrownBy(() -> applyAll(null, null, wc("cost", 0.4, 0.45), wc("time", 0.3, 0.25))).isInstanceOf(ValidationException.class).hasMessageContaining("must list every weight");
        Map<String, Object> w = applyAll(null, null, wc("cost", 0.4, 0.5), wc("time", 0.3, 0.3), wc("soft", 0.2, 0.2));
        assertThat(w).containsEntry("cost", 0.5).containsEntry("time", 0.3).containsEntry("soft", 0.2);
    }

    @Test
    void duplicateNegativeOrOverfullWeightChangesAreRefused() {
        assertThatThrownBy(() -> applyAll(STORED, null, wc("cost", 0.4, 0.45), wc("cost", 0.45, 0.5))).hasMessageContaining("changed twice");
        assertThatThrownBy(() -> applyAll(STORED, null, wc("cost", 0.4, -0.1))).hasMessageContaining("negative");
        assertThatThrownBy(() -> applyAll(STORED, null, wc("cost", 0.4, 0.7), wc("time", 0.25, 0.4))).hasMessageContaining("leaving nothing");
    }

    @Test
    void otherChangesOnTheSameKeyApplyInOrderBeforeTheWeights() {
        Object v = ChangesetApplier.applyAllToConfig("k", List.of("a"), List.of(new PolicyChange.AllowListChange("k", "b", true), new PolicyChange.AllowListChange("k", "a", false)), null);
        assertThat(v).isEqualTo(List.of("b"));
    }

    @Test
    void allowListAddIsIdempotentAndRemoveRemoves() {
        Object added = ChangesetApplier.applyToConfig("k", List.of("a"), new PolicyChange.AllowListChange("k", "b", true));
        assertThat(ChangesetApplier.applyToConfig("k", added, new PolicyChange.AllowListChange("k", "b", true))).isEqualTo(List.of("a", "b"));
        assertThat(ChangesetApplier.applyToConfig("k", added, new PolicyChange.AllowListChange("k", "a", false))).isEqualTo(List.of("b"));
    }
}
