package ai.intentoms.decision.definition;

import ai.intentoms.decision.TestYaml;
import ai.intentoms.decision.model.DecisionDefinition;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DefinitionEditorTest {
    private final DecisionDefinitionParser parser = new DecisionDefinitionParser();

    @Test
    void appliesStructuredEditsAndReserialises() {
        Map<String, Object> edits = new LinkedHashMap<>();
        edits.put("questions.intent.criteria.ship_complete", "All items in a single delivery");
        edits.put("bands.act", 0.85);
        edits.put("rules[0]", Map.of("id", "hazmat", "when", "input.order.has_hazmat == true", "then", "ship_complete"));
        edits.put("timeoutMs", 900);
        String yaml = DefinitionEditor.apply(TestYaml.INTENT, edits);
        DecisionDefinition d = parser.parse(yaml);
        assertThat(d.questions().get("intent").choiceCriteria().get("ship_complete")).isEqualTo("All items in a single delivery");
        assertThat(d.bands().actGte()).isEqualTo(0.85);
        assertThat(d.rules()).hasSize(1);
        assertThat(d.rules().get(0).then()).isEqualTo("ship_complete");
        assertThat(d.timeoutMs()).isEqualTo(900);
        assertThat(DefinitionEditor.normalise("weights.on_time")).isEqualTo("composite.weights.on_time");
        assertThat(DefinitionEditor.normalise("bands.act")).isEqualTo("bands.act.confidence_gte");
    }

    @Test
    void nullRemovesField() {
        Map<String, Object> edits = new LinkedHashMap<>();
        edits.put("questions.is_gift", null);
        DecisionDefinition d = parser.parse(DefinitionEditor.apply(TestYaml.INTENT, edits));
        assertThat(d.questions()).doesNotContainKey("is_gift");
    }
}
