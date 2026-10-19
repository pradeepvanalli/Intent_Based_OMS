package ai.intentoms.decision.runtime;

import ai.intentoms.decision.TestYaml;
import ai.intentoms.decision.definition.DecisionDefinitionParser;
import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.decision.model.DecisionResult;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** OC-01: what a decision record logs as its candidate arms and its applied arm, per decision shape. */
class ArmLoggingTest {
    private final DecisionDefinitionParser parser = new DecisionDefinitionParser();

    private DecisionDefinition demo(String name) { return parser.parse(TestYaml.demo(name)); }

    @Test
    void rankingDecisionsLogEveryCandidate() {
        DecisionDefinition def = demo("sourcing_node_score");
        Map<String, Object> input = Map.of("candidates", List.of(Map.of("id", "DC-NJ"), Map.of("id", "DC-TX"), Map.of("id", "ST-CHI-01")));
        List<String> arms = DecisionRuntime.armsOf(def, input, new LinkedHashMap<>(), "DC-TX", "DC-TX");
        assertThat(arms).containsExactly("DC-NJ", "DC-TX", "ST-CHI-01");
    }

    @Test
    void yesNoDecisionsLogBothAnswers() {
        DecisionDefinition def = demo("payment_risk");
        List<String> arms = DecisionRuntime.armsOf(def, Map.of(), new LinkedHashMap<>(), false, "false");
        assertThat(arms).contains("true", "false");
    }

    @Test
    void choiceDecisionsLogEveryOption() {
        DecisionDefinition def = demo("fulfilment_intent");
        List<String> arms = DecisionRuntime.armsOf(def, Map.of(), new LinkedHashMap<>(Map.of("mainQuestion", "intent")), "ship_complete", "ship_complete");
        assertThat(arms).contains("ship_complete").hasSizeGreaterThan(2);
    }

    @Test
    void anEscalationLogsTheDefaultAsTheAppliedArmAndKeepsTheModelAnswer() {
        DecisionDefinition def = demo("fulfilment_intent");
        Map<String, Object> details = new LinkedHashMap<>();
        String applied = DecisionRuntime.appliedArm(def, "split_ok_answer_that_differs", DecisionResult.ESCALATE, details);
        if (def.dflt() != null) {
            assertThat(applied).isEqualTo(String.valueOf(def.dflt()));
            assertThat(details).containsEntry("modelAnswer", "split_ok_answer_that_differs").containsEntry("chosenArmSource", "default_pending_human");
        }
        Map<String, Object> acted = new LinkedHashMap<>();
        assertThat(DecisionRuntime.appliedArm(def, "ship_complete", DecisionResult.ACT, acted)).isEqualTo("ship_complete");
        assertThat(acted).doesNotContainKey("modelAnswer");
    }
}
