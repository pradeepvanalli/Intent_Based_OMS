package ai.intentoms.agent.reputation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** FD-05: disputes lower an agent's reputation, bounded so one bad week cannot zero a compliant agent. */
class AgentReputationScoreTest {
    @Test void disputeRateIsPerMandate() {
        assertThat(AgentReputationLearner.disputeRate(2, 20)).isEqualTo(0.1);
        assertThat(AgentReputationLearner.disputeRate(0, 0)).isZero();
        assertThat(AgentReputationLearner.disputeRate(50, 10)).isEqualTo(1.0);
    }

    @Test void disputesLowerTheScoreUpToHalf() {
        assertThat(AgentReputationLearner.score(1.0, 0, 0)).isEqualTo(1.0);
        assertThat(AgentReputationLearner.score(1.0, 0, 0.1)).isCloseTo(0.8, within(1e-9));
        assertThat(AgentReputationLearner.score(1.0, 0, 1.0)).isCloseTo(0.5, within(1e-9));
        assertThat(AgentReputationLearner.score(0.6, 0.5, 1.0)).isZero();
    }
}
