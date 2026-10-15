package ai.intentoms.finance.guard;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** GR-05 cap and breaker arithmetic. */
class SpendVerdictTest {
    private static final BigDecimal CAP = new BigDecimal("1000");

    @Test
    void withinCapWhenSpentPlusAmountDoesNotExceedCap() {
        SpendVerdict v = SpendVerdict.evaluate("tenant", CAP, new BigDecimal("900"), new BigDecimal("100"));
        assertThat(v.withinCap()).isTrue();
        assertThat(v.tripsBreaker()).isFalse();
        assertThat(v.remaining()).isEqualByComparingTo("100");
    }

    @Test
    void overCapByOneCentIsRefused() {
        SpendVerdict v = SpendVerdict.evaluate("tenant", CAP, new BigDecimal("900"), new BigDecimal("100.01"));
        assertThat(v.withinCap()).isFalse();
        assertThat(v.tripsBreaker()).isFalse();
    }

    @Test
    void breakerTripsOnlyAbove150PercentOfCap() {
        assertThat(SpendVerdict.breakerThreshold(CAP)).isEqualByComparingTo("1500");
        assertThat(SpendVerdict.evaluate("tenant", CAP, new BigDecimal("1500"), BigDecimal.ONE).tripsBreaker()).isFalse();
        assertThat(SpendVerdict.evaluate("tenant", CAP, new BigDecimal("1500.01"), BigDecimal.ONE).tripsBreaker()).isTrue();
        assertThat(SpendVerdict.evaluate("tenant", CAP, new BigDecimal("1200"), BigDecimal.ONE).withinCap()).isFalse();
        assertThat(SpendVerdict.evaluate("tenant", CAP, new BigDecimal("1200"), BigDecimal.ONE).tripsBreaker()).isFalse();
    }

    @Test
    void remainingNeverNegative() {
        assertThat(SpendVerdict.evaluate("actor", CAP, new BigDecimal("1300"), BigDecimal.TEN).remaining()).isEqualByComparingTo("0");
    }

    @Test
    void zeroCapRefusesEverything() {
        assertThat(SpendVerdict.evaluate("agent", BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("0.01")).withinCap()).isFalse();
        assertThat(SpendVerdict.evaluate("agent", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO).withinCap()).isTrue();
    }
}
