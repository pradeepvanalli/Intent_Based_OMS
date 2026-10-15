package ai.intentoms.decision.runtime;

import ai.intentoms.decision.TestYaml;
import ai.intentoms.decision.definition.DecisionDefinitionParser;
import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.platform.pii.Tokenizer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StateBuilderTest {
    private final Tokenizer fakeTokenizer = new Tokenizer(null) {
        @Override public String token(String kind, String value) { return kind + "_" + Integer.toHexString(value.hashCode()); }
    };
    private final StateBuilder builder = new StateBuilder(fakeTokenizer);

    @Test
    @SuppressWarnings("unchecked")
    void projectsIncludePathsWithWildcardAndTokenisesPii() {
        DecisionDefinition def = new DecisionDefinitionParser().parse(TestYaml.INTENT);
        Map<String, Object> input = Map.of(
            "order", Map.of("channel", "web", "notes", "gift for mum", "total", 99.0, "ship_to", Map.of("name", "Jane Doe", "email", "jane@example.com")),
            "customer", Map.of("tier", "gold", "email", "jane@example.com", "name", "Jane"),
            "plan_options", List.of(Map.of("eta", "2026-10-07", "cost", 5.0), Map.of("eta", "2026-10-09", "cost", 3.0)));
        Map<String, Object> state = builder.build(def, input);
        Map<String, Object> order = (Map<String, Object>) state.get("order");
        assertThat(order).containsEntry("channel", "web").containsEntry("notes", "gift for mum").doesNotContainKey("total").doesNotContainKey("ship_to");
        Map<String, Object> customer = (Map<String, Object>) state.get("customer");
        assertThat(customer).containsEntry("tier", "gold").doesNotContainKey("email");
        List<Map<String, Object>> plans = (List<Map<String, Object>>) state.get("plan_options");
        assertThat(plans).hasSize(2);
        assertThat(plans.get(0)).containsEntry("eta", "2026-10-07").doesNotContainKey("cost");
    }

    @Test
    @SuppressWarnings("unchecked")
    void wholeInputIsTokenisedWhenNoIncludeList() {
        DecisionDefinition def = new DecisionDefinitionParser().parse("decision: x\nversion: 1\ntier: T0\nquestions:\n  n: { type: noul, statement: s }\n");
        Map<String, Object> state = builder.build(def, Map.of("customer", Map.of("email", "a@b.c", "first_name", "Ann", "phone", "555", "street", "1 Main St", "tier", "gold"),
            "contacts", List.of(Map.of("name", "Bob"))));
        Map<String, Object> c = (Map<String, Object>) state.get("customer");
        assertThat(c.get("email")).asString().startsWith("em_");
        assertThat(c.get("first_name")).asString().startsWith("nm_");
        assertThat(c.get("phone")).asString().startsWith("ph_");
        assertThat(c.get("street")).asString().startsWith("ad_");
        assertThat(c.get("tier")).isEqualTo("gold");
        assertThat(((List<Map<String, Object>>) state.get("contacts")).get(0).get("name")).asString().startsWith("nm_");
    }
}
