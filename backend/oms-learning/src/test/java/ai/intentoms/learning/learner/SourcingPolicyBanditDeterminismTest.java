package ai.intentoms.learning.learner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** NF-04: the bandit's exploration draw must be replayable from a stored seed, not a fresh shared RNG each time. */
class SourcingPolicyBanditDeterminismTest {
    private static final List<String> ARMS = List.of("ship_complete", "ship_as_available", "split_up_to_k");
    private static final Map<String, Double> WEIGHTS = Map.of("ship_complete", 0.7, "ship_as_available", 0.2, "split_up_to_k", 0.1);

    @RepeatedTest(20)
    void sameSeedReproducesSameArm() {
        long seed = new SecureRandom().nextLong();
        String first = SourcingPolicyBandit.replay(ARMS, WEIGHTS, seed);
        String second = SourcingPolicyBandit.replay(ARMS, WEIGHTS, seed);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void differentSeedsCanDisagree() {
        // Not a strict guarantee for any two seeds, but over many genuinely random seeds (not sequential small
        // integers, which can correlate badly with java.util.Random's LCG) we must see more than one arm chosen —
        // otherwise exploration isn't actually happening. Matches production, which seeds from SecureRandom.nextLong().
        SecureRandom seedSource = new SecureRandom();
        var chosen = new java.util.HashSet<String>();
        for (int i = 0; i < 300; i++) chosen.add(SourcingPolicyBandit.replay(ARMS, WEIGHTS, seedSource.nextLong()));
        assertThat(chosen.size()).isGreaterThan(1);
    }

    @Test
    void everyEligibleArmHasANonZeroFloor() {
        // The "feedback loop" mitigation (§11): no arm may be mathematically unreachable.
        SecureRandom seedSource = new SecureRandom();
        var counts = new java.util.HashMap<String, Integer>();
        for (int i = 0; i < 5000; i++) counts.merge(SourcingPolicyBandit.replay(ARMS, WEIGHTS, seedSource.nextLong()), 1, Integer::sum);
        for (String arm : ARMS) assertThat(counts.getOrDefault(arm, 0)).as("arm " + arm + " must be reachable").isGreaterThan(0);
    }
}
