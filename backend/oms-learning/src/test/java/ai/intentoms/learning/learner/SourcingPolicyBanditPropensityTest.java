package ai.intentoms.learning.learner;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** OC-01: the logged propensity must be the true probability that epsilon-greedy draws the arm. */
class SourcingPolicyBanditPropensityTest {
    private static final List<String> ARMS = List.of("ship_complete", "split_up_to_k", "nearest_node");
    private static final Map<String, Double> WEIGHTS = Map.of("ship_complete", 0.6, "split_up_to_k", 0.3, "nearest_node", 0.1);

    @Test void drawProbabilitiesSumToOneAndFavourTheTopArm() {
        double e = SourcingPolicyBandit.DEFAULT_EXPLORATION;
        double top = SourcingPolicyBandit.drawProbability("ship_complete", ARMS, WEIGHTS, e);
        double other = SourcingPolicyBandit.drawProbability("nearest_node", ARMS, WEIGHTS, e);
        assertThat(top).isCloseTo(1 - e + e / 3, within(1e-12));
        assertThat(other).isCloseTo(e / 3, within(1e-12));
        double sum = ARMS.stream().mapToDouble(a -> SourcingPolicyBandit.drawProbability(a, ARMS, WEIGHTS, e)).sum();
        assertThat(sum).isCloseTo(1.0, within(1e-12));
    }

    @Test void loggedProbabilityMatchesTheEmpiricalDrawRate() {
        Random seeds = new Random(7);
        int n = 20_000, top = 0;
        for (int i = 0; i < n; i++) if ("ship_complete".equals(SourcingPolicyBandit.replay(ARMS, WEIGHTS, seeds.nextLong()))) top++;
        assertThat((double) top / n).isCloseTo(SourcingPolicyBandit.drawProbability("ship_complete", ARMS, WEIGHTS, SourcingPolicyBandit.DEFAULT_EXPLORATION), within(0.01));
    }

    @Test void singleArmIsCertain() {
        assertThat(SourcingPolicyBandit.drawProbability("a", List.of("a"), Map.of(), 0.1)).isEqualTo(1.0);
    }
}
