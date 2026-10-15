package ai.intentoms.decision.rules;

import ai.intentoms.decision.model.RuleDef;
import ai.intentoms.platform.cel.CelEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuleEngineTest {
    private final CelEngine cel = new CelEngine();
    private final RuleEngine engine = new RuleEngine(cel);

    @Test
    void firstMatchByDeclarationOrderAfterPrioritySort() {
        List<RuleDef> rules = List.of(
            new RuleDef("window", "input.return.days_since_delivery > input.policy.window_days", "deny_out_of_window", "out_of_window", 10),
            new RuleDef("final", "input.order.final_sale == true", "deny_final_sale", "final_sale", 20));
        Map<String, Object> input = Map.of("return", Map.of("days_since_delivery", 45), "policy", Map.of("window_days", 30), "order", Map.of("final_sale", true));
        var m = engine.firstMatch(rules, CelVars.base(input, Map.of(), Map.of()));
        assertThat(m).isPresent();
        assertThat(m.get().rule().id()).isEqualTo("window");
    }

    @Test
    void noMatchReturnsEmpty() {
        List<RuleDef> rules = List.of(new RuleDef("r", "input.x > 5", "escalate", null, 1));
        assertThat(engine.firstMatch(rules, CelVars.base(Map.of("x", 1), Map.of(), Map.of()))).isEmpty();
    }

    @Test
    void erroringRuleIsSkippedNotMatched() {
        List<RuleDef> rules = List.of(
            new RuleDef("broken", "input.missing.key > 1", "block", null, 1),
            new RuleDef("ok", "input.x == 1", "value", null, 2));
        var m = engine.firstMatch(rules, CelVars.base(Map.of("x", 1), Map.of(), Map.of()));
        assertThat(m).isPresent();
        assertThat(m.get().rule().id()).isEqualTo("ok");
        assertThat(m.get().errored()).containsExactly("broken");
    }

    @Test
    void standardRootsAreBoundFromInput() {
        List<RuleDef> rules = List.of(new RuleDef("r", "order.total > 100 && chosen_plan.margin < tenant.margin_floor", "escalate", null, 1));
        Map<String, Object> input = Map.of("order", Map.of("total", 500), "plan", Map.of("margin", 0.05));
        var vars = CelVars.base(input, Map.of("margin_floor", 0.12), Map.of());
        assertThat(engine.firstMatch(rules, vars)).isPresent();
    }
}
