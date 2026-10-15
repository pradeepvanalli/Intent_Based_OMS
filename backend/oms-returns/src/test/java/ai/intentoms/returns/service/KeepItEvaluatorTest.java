package ai.intentoms.returns.service;

import ai.intentoms.finance.model.Money;
import ai.intentoms.returns.model.KeepIt;
import ai.intentoms.returns.model.ReturnLine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-RET-05 keep-it economics: return cost vs recovery, low risk only. */
class KeepItEvaluatorTest {
    private static ReturnLine line(String price, int qty, Map<String, Object> attrs) {
        return new ReturnLine("L1", "SKU", "d", qty, Money.usd(price), Money.zero("USD"), "changed_mind", null, null, null, attrs);
    }

    @Test
    void cheapItemWithLowRiskIsKept() {
        KeepIt k = KeepItEvaluator.evaluate(List.of(line("18.00", 1, Map.of())), 0.0, 8.5, 2.0, 0.4, true);
        assertThat(k.offered()).isTrue();
        assertThat(k.reason()).isEqualTo("return_cost_exceeds_recovery");
        assertThat(k.returnCostUsd()).isEqualByComparingTo("10.50");
        assertThat(k.recoveryValueUsd()).isEqualByComparingTo("7.20");
    }

    @Test
    void valuableItemIsNotKeptBecauseRecoveryBeatsCost() {
        KeepIt k = KeepItEvaluator.evaluate(List.of(line("45.00", 1, Map.of())), 0.0, 8.5, 2.0, 0.4, true);
        assertThat(k.offered()).isFalse();
        assertThat(k.reason()).isEqualTo("recovery_exceeds_cost");
        assertThat(k.recoveryValueUsd()).isEqualByComparingTo("18.00");
    }

    @Test
    void riskHazmatUnknownAndDisabledBlockTheOffer() {
        assertThat(KeepItEvaluator.evaluate(List.of(line("18.00", 1, Map.of())), 1.0, 8.5, 2.0, 0.4, true).reason()).isEqualTo("risk_too_high");
        assertThat(KeepItEvaluator.evaluate(List.of(line("18.00", 1, Map.of())), null, 8.5, 2.0, 0.4, true).reason()).isEqualTo("risk_unknown");
        assertThat(KeepItEvaluator.evaluate(List.of(line("18.00", 1, Map.of("hazmat", true))), 0.0, 8.5, 2.0, 0.4, true).reason()).isEqualTo("hazmat");
        assertThat(KeepItEvaluator.evaluate(List.of(line("18.00", 1, Map.of())), 0.0, 8.5, 2.0, 0.4, false).reason()).isEqualTo("disabled");
    }

    @Test
    void recoveryValueDependsOnCondition() {
        List<ReturnLine> lines = List.of(line("100.00", 2, Map.of()));
        assertThat(KeepItEvaluator.recoveryValue(lines, 0.4, "new")).isEqualByComparingTo("80.00");
        assertThat(KeepItEvaluator.recoveryValue(lines, 0.4, "like_new")).isEqualByComparingTo("60.00");
        assertThat(KeepItEvaluator.recoveryValue(lines, 0.4, "damaged")).isEqualByComparingTo("32.00");
        assertThat(KeepItEvaluator.recoveryValue(lines, 0.4, "wrong_item")).isEqualByComparingTo("0.00");
    }
}
