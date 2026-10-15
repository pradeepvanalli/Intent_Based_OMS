package ai.intentoms.returns.service;

import ai.intentoms.returns.model.RefundTiming;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-RET-02 score → timing under the T2 autonomy rule (act only in the ACT band). */
class RefundTimingMapperTest {
    @Test
    void scoreBandsWhenTheDecisionActs() {
        assertThat(RefundTimingMapper.map(0, true, false)).isEqualTo(RefundTiming.INSTANT);
        assertThat(RefundTimingMapper.map(0.99, true, false)).isEqualTo(RefundTiming.INSTANT);
        assertThat(RefundTimingMapper.map(1.0, true, false)).isEqualTo(RefundTiming.ON_CARRIER_SCAN);
        assertThat(RefundTimingMapper.map(1.9, true, false)).isEqualTo(RefundTiming.ON_CARRIER_SCAN);
        assertThat(RefundTimingMapper.map(2.0, true, false)).isEqualTo(RefundTiming.ON_INSPECTION);
        assertThat(RefundTimingMapper.map(2.99, true, false)).isEqualTo(RefundTiming.ON_INSPECTION);
        assertThat(RefundTimingMapper.map(3.0, true, false)).isEqualTo(RefundTiming.STORE_CREDIT_ONLY);
        assertThat(RefundTimingMapper.map(4.0, true, false)).isEqualTo(RefundTiming.STORE_CREDIT_ONLY);
    }

    @Test
    void nonActBandsFallBackToInspectionUnlessAHumanIsRequired() {
        assertThat(RefundTimingMapper.map(0, false, false)).as("confirm/default band never grants an instant refund").isEqualTo(RefundTiming.ON_INSPECTION);
        assertThat(RefundTimingMapper.map(4, false, false)).isEqualTo(RefundTiming.ON_INSPECTION);
        assertThat(RefundTimingMapper.map(0, false, true)).isEqualTo(RefundTiming.ESCALATE);
        assertThat(RefundTimingMapper.map(2.5, false, true)).isEqualTo(RefundTiming.ESCALATE);
    }

    @Test
    void riskLevelsFollowTheSameBoundaries() {
        assertThat(RefundTimingMapper.level(0.5)).isEqualTo("low");
        assertThat(RefundTimingMapper.level(1.5)).isEqualTo("medium");
        assertThat(RefundTimingMapper.level(2.5)).isEqualTo("high");
        assertThat(RefundTimingMapper.level(3.5)).isEqualTo("severe");
    }
}
