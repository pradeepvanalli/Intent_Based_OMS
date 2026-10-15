package ai.intentoms.decision.runtime;

import ai.intentoms.decision.model.Answer;
import ai.intentoms.decision.model.Band;
import ai.intentoms.decision.model.Bands;
import ai.intentoms.decision.model.Tier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class BandsAndAnswerTest {
    @Test
    void bandMapping() {
        Bands b = Tier.T1.defaultBands();
        assertThat(b.of(0.95)).isEqualTo(Band.ACT);
        assertThat(b.of(0.8)).isEqualTo(Band.ACT);
        assertThat(b.of(0.79)).isEqualTo(Band.CONFIRM);
        assertThat(b.of(0.5)).isEqualTo(Band.CONFIRM);
        assertThat(b.of(0.49)).isEqualTo(Band.ESCALATE);
        assertThat(Tier.T0.defaultBands().of(0.5)).isEqualTo(Band.ACT);
        assertThat(Tier.T0.defaultBands().of(0.49)).isEqualTo(Band.ESCALATE);
        assertThat(Tier.T2.defaultBands().of(0.89)).isEqualTo(Band.CONFIRM);
        assertThat(Tier.T3.defaultBands().of(1.0)).isEqualTo(Band.CONFIRM);
    }

    @Test
    void noulConfidenceIsTwoPMinusOne() {
        assertThat(Answer.noul(0.95).confidence()).isCloseTo(0.9, within(1e-9));
        assertThat(Answer.noul(0.05).confidence()).isCloseTo(0.9, within(1e-9));
        assertThat(Answer.noul(0.5).confidence()).isCloseTo(0.0, within(1e-9));
        assertThat(Answer.noul(0.75).probabilities()).containsEntry("true", 0.75).containsEntry("false", 0.25);
        assertThat(Answer.noul(0.75).asBoolean()).isTrue();
        assertThat(Answer.noul(0.2).asBoolean()).isFalse();
    }

    @Test
    void confidenceIsClamped() {
        assertThat(Answer.choice("a", null, 1.7).confidence()).isEqualTo(1.0);
        assertThat(Answer.choice("a", null, -0.2).confidence()).isEqualTo(0.0);
    }

    @Test
    void canaryAssignmentIsStable() {
        for (int i = 0; i < 50; i++) {
            String subject = "ord_" + i;
            assertThat(DecisionRuntime.inCanary(subject, 30)).isEqualTo(DecisionRuntime.inCanary(subject, 30));
        }
        assertThat(DecisionRuntime.inCanary("x", 0)).isFalse();
        assertThat(DecisionRuntime.inCanary("x", 100)).isTrue();
    }
}
