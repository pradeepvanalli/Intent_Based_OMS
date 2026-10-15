package ai.intentoms.monitoring.alert;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-MON-06 de-duplication window. */
class AlertDedupPolicyTest {
    private final Instant now = Instant.parse("2026-10-05T10:00:00Z");

    @Test
    void suppressesRepeatsInsideTheWindowOnly() {
        Duration window = Duration.ofMinutes(15);
        assertThat(AlertDedupPolicy.suppress(now.minusSeconds(60), now, window)).isTrue();
        assertThat(AlertDedupPolicy.suppress(now.minus(Duration.ofMinutes(15)), now, window)).as("boundary is inclusive").isTrue();
        assertThat(AlertDedupPolicy.suppress(now.minus(Duration.ofMinutes(16)), now, window)).isFalse();
        assertThat(AlertDedupPolicy.suppress(null, now, window)).isFalse();
        assertThat(AlertDedupPolicy.suppress(now.minusSeconds(1), now, Duration.ZERO)).as("zero window disables de-dup").isFalse();
    }
}
