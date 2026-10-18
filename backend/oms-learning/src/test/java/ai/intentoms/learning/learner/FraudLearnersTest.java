package ai.intentoms.learning.learner;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** FD-03 expected-loss threshold search and FD-07 population stability index. */
class FraudLearnersTest {

    private static List<FraudThresholdTuner.Case> cases() {
        List<FraudThresholdTuner.Case> c = new ArrayList<>();
        // Good orders the model wrongly calls risky with middling confidence: blocked at 0.90, not at 0.95.
        for (int i = 0; i < 10; i++) c.add(new FraudThresholdTuner.Case(0.92, true, false, 100));
        // Good orders the model is right about.
        for (int i = 0; i < 80; i++) c.add(new FraudThresholdTuner.Case(0.95, false, false, 100));
        // Real fraud the model catches with high confidence.
        for (int i = 0; i < 10; i++) c.add(new FraudThresholdTuner.Case(0.97, true, true, 400));
        return c;
    }

    @Test void raisesTheThresholdWhenTooManyGoodOrdersAreBlocked() {
        // At 0.90: 10 of 90 good orders blocked (11%), above a 5% target. At 0.93+: none blocked, fraud still caught.
        FraudThresholdTuner.Choice c = FraudThresholdTuner.optimise(cases(), 0.90, 0.05, 25, 0.05);
        assertThat(c.currentFpRate()).isGreaterThan(0.05);
        assertThat(c.threshold()).isGreaterThanOrEqualTo(0.93).isLessThanOrEqualTo(0.95);
        assertThat(c.fpRate()).isZero();
        assertThat(c.fnRate()).isZero();
        assertThat(c.cost()).isLessThan(c.currentCost());
    }

    @Test void staysPutWhenAlreadyOptimal() {
        FraudThresholdTuner.Choice c = FraudThresholdTuner.optimise(cases(), 0.94, 0.05, 25, 0.05);
        assertThat(c.threshold()).isEqualTo(0.94);
    }

    @Test void neverMovesMoreThanOneStep() {
        FraudThresholdTuner.Choice c = FraudThresholdTuner.optimise(cases(), 0.60, 0.05, 25, 0.05);
        assertThat(c.threshold()).isLessThanOrEqualTo(0.65);
    }

    @Test void psiIsZeroForTheSameDistributionAndLargeForAShift() {
        double[] base = {70, 20, 8, 2};
        assertThat(FraudDriftMonitor.psi(base, new double[]{70, 20, 8, 2})).isZero();
        assertThat(FraudDriftMonitor.psi(base, new double[]{20, 20, 20, 40})).isGreaterThan(0.25);
    }

    @Test void featurePsiFlagsAVelocitySpike() {
        List<Map<String, Object>> baseline = new ArrayList<>(), recent = new ArrayList<>();
        for (int i = 0; i < 100; i++) baseline.add(Map.of("payment", Map.of("velocity_1h", 0, "velocity_24h", 0), "order", Map.of("total", 80.0)));
        for (int i = 0; i < 100; i++) recent.add(Map.of("payment", Map.of("velocity_1h", i % 2 == 0 ? 3 : 0, "velocity_24h", 0), "order", Map.of("total", 80.0)));
        Map<String, Double> psi = FraudDriftMonitor.featurePsi(baseline, recent);
        assertThat(psi.get("payment.velocity_1h")).isGreaterThan(0.25);
        assertThat(psi.get("order.total")).isZero();
        assertThat(FraudDriftMonitor.featurePsi(baseline.subList(0, 10), recent)).isEmpty();
    }
}
