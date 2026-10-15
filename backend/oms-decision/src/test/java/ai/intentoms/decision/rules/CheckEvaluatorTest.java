package ai.intentoms.decision.rules;

import ai.intentoms.decision.model.Answer;
import ai.intentoms.decision.model.CheckResult;
import ai.intentoms.platform.cel.CelEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CheckEvaluatorTest {
    private final CheckEvaluator checks = new CheckEvaluator(new CelEngine());

    @Test
    void postChecksSeeAnswersAndDecision() {
        Map<String, Object> base = CelVars.base(Map.of("order", Map.of("has_hazmat", true), "chosen_plan", Map.of("packages", 2, "margin", 0.2)), Map.of("margin_floor", 0.12), Map.of());
        Map<String, Object> vars = CelVars.withAnswers(base, Map.of("intent", Answer.choice("ship_as_available", Map.of(), 0.9)), "ship_as_available", 0.9, "ACT");
        List<CheckResult> r = checks.evaluate(List.of(
            "chosen_plan.margin >= tenant.margin_floor",
            "!(order.has_hazmat && chosen_plan.packages > 1)",
            "answers.intent == decision.value",
            "decision.confidence >= 0.8"), vars);
        assertThat(r).extracting(CheckResult::passed).containsExactly(true, false, true, true);
        assertThat(CheckEvaluator.allPassed(r)).isFalse();
    }

    @Test
    void erroringCheckCountsAsFailed() {
        List<CheckResult> r = checks.evaluate(List.of("input.nope.deeper > 1"), CelVars.base(Map.of(), Map.of(), Map.of()));
        assertThat(r.get(0).passed()).isFalse();
        assertThat(r.get(0).error()).isNotBlank();
    }
}
