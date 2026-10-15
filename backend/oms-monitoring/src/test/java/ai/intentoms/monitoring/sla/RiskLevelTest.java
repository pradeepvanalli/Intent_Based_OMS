package ai.intentoms.monitoring.sla;

import ai.intentoms.decision.model.Answer;
import ai.intentoms.decision.model.Band;
import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.model.Layer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-MON-02 score to level mapping and score extraction from a decision result. */
class RiskLevelTest {
    @Test
    void levelThresholds() {
        assertThat(RiskLevel.of(0)).isEqualTo(RiskLevel.LOW);
        assertThat(RiskLevel.of(0.9)).isEqualTo(RiskLevel.LOW);
        assertThat(RiskLevel.of(1)).isEqualTo(RiskLevel.MEDIUM);
        assertThat(RiskLevel.of(2)).isEqualTo(RiskLevel.HIGH);
        assertThat(RiskLevel.of(3)).isEqualTo(RiskLevel.CRITICAL);
        assertThat(RiskLevel.of(4)).isEqualTo(RiskLevel.CRITICAL);
        assertThat(RiskLevel.of(3).key()).isEqualTo("critical");
        assertThat(RiskLevel.HIGH.atRisk()).isTrue();
        assertThat(RiskLevel.MEDIUM.atRisk()).isFalse();
    }

    @Test
    void scoreComesFromValueThenAnswerThenDefault() {
        assertThat(BreachRiskScorer.scoreOf(result(3, Map.of()))).isEqualTo(3);
        assertThat(BreachRiskScorer.scoreOf(result("1", Map.of()))).isEqualTo(1);
        assertThat(BreachRiskScorer.scoreOf(result(null, Map.of("breach_risk", Answer.score(4, Map.of(), 0.9, Map.of()))))).isEqualTo(4);
        assertThat(BreachRiskScorer.scoreOf(result(null, Map.of()))).isEqualTo(1);
        assertThat(BreachRiskScorer.scoreOf(result(9, Map.of()))).isEqualTo(4);
        assertThat(BreachRiskScorer.scoreOf(result(-2, Map.of()))).isEqualTo(0);
    }

    private static DecisionResult result(Object value, Map<String, Answer> answers) {
        return new DecisionResult("rec", "sla_breach_risk", 1, Layer.L1_SYSTEM_ONE, Band.ACT, value, answers, 0.9, "act", false, null, List.of(), List.of(), 5, 0, "heuristic", "h1", Map.of());
    }
}
