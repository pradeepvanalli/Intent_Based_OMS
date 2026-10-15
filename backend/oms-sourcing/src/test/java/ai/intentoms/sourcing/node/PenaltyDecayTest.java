package ai.intentoms.sourcing.node;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** FR-SRC-07 learned penalty decay: 7-day half-life. */
class PenaltyDecayTest {
    @Test
    void halvesEverySevenDays() {
        Instant t0 = Instant.parse("2026-10-01T00:00:00Z");
        assertThat(PenaltyDecay.effective(0.8, t0, t0)).isEqualTo(0.8);
        assertThat(PenaltyDecay.effective(0.8, t0, t0.plus(Duration.ofDays(7)))).isCloseTo(0.4, within(1e-9));
        assertThat(PenaltyDecay.effective(0.8, t0, t0.plus(Duration.ofDays(14)))).isCloseTo(0.2, within(1e-9));
        assertThat(PenaltyDecay.effective(0.8, t0, t0.plus(Duration.ofHours(84)))).isCloseTo(0.8 * Math.pow(0.5, 0.5), within(1e-9));
        assertThat(PenaltyDecay.effective(0.8, t0, t0.minus(Duration.ofDays(1)))).isEqualTo(0.8); // clock skew never inflates
        assertThat(PenaltyDecay.effective(0.0, t0, t0.plus(Duration.ofDays(7)))).isZero();
        assertThat(PenaltyDecay.clamp(1.7)).isEqualTo(1.0);
    }
}
