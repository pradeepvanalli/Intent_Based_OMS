package ai.intentoms.monitoring.playbook;

import ai.intentoms.platform.cel.CelEngine;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-MON-04 gates: minConfidence, when (CEL), onFailure and spend limits, evaluated without Spring. */
class PlaybookGateTest {
    private final PlaybookGate gate = new PlaybookGate(new CelEngine());
    private final Map<String, Object> limits = Map.of("reship_usd", 150, "refund_usd", 100);

    private static Map<String, Object> vars(long ageMinutes, String severity) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("exception", Map.of("age_minutes", ageMinutes, "severity", severity, "type", "store_reject"));
        vars.put("order", Map.of("channel", "web", "total", 58.0));
        return vars;
    }

    @Test
    void minConfidenceBlocksLowConfidence() {
        Map<String, Object> step = Map.of("action", "resource", "minConfidence", 0.8);
        PlaybookGate.Verdict low = gate.evaluate(step, 0.58, vars(0, "high"), false, limits);
        assertThat(low.pass()).isFalse();
        assertThat(low.gatedBy()).isEqualTo("minConfidence");
        assertThat(gate.evaluate(step, 0.85, vars(0, "high"), false, limits).pass()).isTrue();
        assertThat(gate.evaluate(Map.of("action", "resource", "minConfidence", 0.0), 0.0, vars(0, "high"), false, limits).pass()).isTrue();
    }

    @Test
    void whenIsCelOverExceptionAndOrder() {
        Map<String, Object> step = Map.of("action", "resource", "when", "exception.age_minutes > 240");
        assertThat(gate.evaluate(step, 1.0, vars(10, "medium"), false, limits).gatedBy()).isEqualTo("when");
        assertThat(gate.evaluate(step, 1.0, vars(300, "medium"), false, limits).pass()).isTrue();
        assertThat(gate.evaluate(Map.of("action", "x", "when", "exception.severity == 'high' && order.channel == 'web'"), 1.0, vars(0, "high"), false, limits).pass()).isTrue();
        PlaybookGate.Verdict broken = gate.evaluate(Map.of("action", "x", "when", "exception.age_minutes >"), 1.0, vars(0, "high"), false, limits);
        assertThat(broken.pass()).isFalse();
        assertThat(broken.gatedBy()).isEqualTo("when");
        assertThat(broken.detail()).contains("error");
    }

    @Test
    void onFailureRunsOnlyAfterAFailedStep() {
        Map<String, Object> step = Map.of("action", "create_task", "onFailure", true);
        assertThat(gate.evaluate(step, 1.0, vars(0, "high"), false, limits).gatedBy()).isEqualTo("onFailure");
        assertThat(gate.evaluate(step, 1.0, vars(0, "high"), true, limits).pass()).isTrue();
    }

    @Test
    void spendLimitsGateMoneyMovingSteps() {
        assertThat(gate.evaluate(Map.of("action", "reship", "amountUsd", 200), 1.0, vars(0, "high"), false, limits).gatedBy()).isEqualTo("spendLimit");
        assertThat(gate.evaluate(Map.of("action", "reship", "amountUsd", 120), 1.0, vars(0, "high"), false, limits).pass()).isTrue();
        assertThat(gate.evaluate(Map.of("action", "refund", "amountUsd", 101), 1.0, vars(0, "high"), false, limits).pass()).isFalse();
        assertThat(gate.evaluate(Map.of("action", "contact_customer", "amountUsd", 30, "spendCategory", "appeasement_usd"), 1.0, vars(0, "high"), false, limits).pass())
            .as("no appeasement limit configured -> allowed").isTrue();
    }
}
