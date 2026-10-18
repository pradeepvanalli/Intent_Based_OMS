package ai.intentoms.learning.experiment;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class MsprtTest {
    private static List<Double> normal(Random rng, int n, double mean, double sd) {
        List<Double> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(mean + rng.nextGaussian() * sd);
        return out;
    }

    @Test
    void aClearEffectStopsTheTestInTheRightDirection() {
        Random rng = new Random(11);
        assertThat(Msprt.test(normal(rng, 200, 1.2, 0.5), normal(rng, 200, 1.0, 0.5), 0.05, 30, null).verdict()).isEqualTo(Msprt.Verdict.TREATMENT_BETTER);
        assertThat(Msprt.test(normal(rng, 200, 0.8, 0.5), normal(rng, 200, 1.0, 0.5), 0.05, 30, null).verdict()).isEqualTo(Msprt.Verdict.TREATMENT_WORSE);
    }

    @Test
    void peekingAtEveryStepUnderTheNullRarelyStops() {
        Random rng = new Random(7);
        int falseStops = 0, runs = 200;
        for (int run = 0; run < runs; run++) {
            List<Double> t = new ArrayList<>(), c = new ArrayList<>();
            for (int step = 0; step < 20; step++) {
                t.addAll(normal(rng, 20, 1.0, 0.5));
                c.addAll(normal(rng, 20, 1.0, 0.5));
                if (Msprt.test(t, c, 0.05, 30, null).verdict() != Msprt.Verdict.CONTINUE && t.size() >= 30) { falseStops++; break; }
            }
        }
        assertThat(falseStops / (double) runs).isLessThan(0.08); // always valid: ~alpha even with 20 looks
    }

    @Test
    void tooFewOutcomesIsReportedAsSuch() {
        assertThat(Msprt.test(List.of(1.0, 2.0), List.of(1.0), 0.05, 30, null).verdict()).isEqualTo(Msprt.Verdict.INSUFFICIENT_DATA);
    }
}
