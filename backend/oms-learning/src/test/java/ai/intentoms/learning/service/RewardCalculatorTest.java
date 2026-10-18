package ai.intentoms.learning.service;

import ai.intentoms.learning.model.ObjectiveWeights;
import ai.intentoms.learning.model.OutcomeData;
import ai.intentoms.learning.model.OutcomeRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RewardCalculatorTest {
    private static OutcomeRecord out(OutcomeData d) {
        return new OutcomeRecord("o", "e", d.getClass().getSimpleName(), d, Instant.now(), "ev", Instant.now());
    }

    @Test void everyTermIsNormalisedToZeroOne() {
        var outs = List.of(
            out(new OutcomeData.FulfillmentOutcome(2, false, List.of("n1", "n2"), false, 4, 3, 30.0, 120.0)),
            out(new OutcomeData.DeliveryOutcome(true, 0, false, false)),
            out(new OutcomeData.ContactOutcome("cs", "wismo", false)),
            out(new OutcomeData.PaymentOutcome(true, false, 500.0)));
        Map<String, Double> c = RewardCalculator.components(outs);
        assertThat(c.get("fill")).isEqualTo(0.75);
        assertThat(c.get("cost")).isEqualTo(0.25);
        assertThat(c.get("split")).isEqualTo(0.5);
        assertThat(c.get("otd")).isEqualTo(1.0);
        assertThat(c.get("cx")).isEqualTo(0.5);
        assertThat(c.get("fraud")).isEqualTo(1.0); // 500 loss on a 120 order caps at 1
        c.values().forEach(v -> assertThat(v).isBetween(0.0, 1.0));
    }

    @Test void unreportedFiguresLeaveTheirTermsUnscored() {
        var outs = List.of(out(new OutcomeData.FulfillmentOutcome(1, false, List.of(), false, 0, 0, 0, 0)));
        assertThat(RewardCalculator.scoredTerms(outs)).containsExactly("split");
        Map<String, Double> c = RewardCalculator.components(outs);
        assertThat(c.get("fill")).isZero();
        assertThat(c.get("cost")).isZero();
        assertThat(c.get("split")).isZero();
    }

    @Test void rewardAppliesWeightsWithSigns() {
        Map<String, Double> c = Map.of("fill", 1.0, "otd", 1.0, "cost", 0.2, "split", 0.5, "fraud", 0.0, "cx", 0.0, "inv", 0.0);
        assertThat(RewardCalculator.compute(c, ObjectiveWeights.preset("balanced"))).isCloseTo(1.0 + 1.0 - 0.2 - 0.5, within(1e-9));
    }

    @Test void maturityWindowsPerTerm() {
        MaturityWindows w = MaturityWindows.DEFAULT;
        assertThat(w.forTerm("otd").toDays()).isEqualTo(14);
        assertThat(w.forTerm("cx").toDays()).isEqualTo(30);
        assertThat(w.forTerm("fraud").toDays()).isEqualTo(120);
        assertThat(w.longest().toDays()).isEqualTo(120);
    }
}
