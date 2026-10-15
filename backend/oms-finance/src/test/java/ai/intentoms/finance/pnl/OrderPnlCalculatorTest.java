package ai.intentoms.finance.pnl;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static ai.intentoms.finance.pnl.DefaultOrderPnlCalculator.DEFAULT_PAYMENT_FEE_FIXED;
import static ai.intentoms.finance.pnl.DefaultOrderPnlCalculator.DEFAULT_PAYMENT_FEE_RATE;
import static ai.intentoms.finance.pnl.DefaultOrderPnlCalculator.DEFAULT_RETURNS_RESERVE_RATE;
import static org.assertj.core.api.Assertions.assertThat;

/** FR-FIN-04 contribution margin arithmetic. */
class OrderPnlCalculatorTest {

    private static Pnl compute(PnlInput in) {
        return DefaultOrderPnlCalculator.calculate(in, DEFAULT_PAYMENT_FEE_RATE, DEFAULT_PAYMENT_FEE_FIXED, DEFAULT_RETURNS_RESERVE_RATE);
    }

    @Test
    void healthyOrder() {
        PnlInput in = new PnlInput(
            List.of(PnlLine.of(2, "50.00", "10.00", "20.00"), PnlLine.of(1, "30.00", null, "12.00")),
            new BigDecimal("5.99"), new BigDecimal("7.50"), new BigDecimal("2.00"), new BigDecimal("1.25"),
            null, null, null, new BigDecimal("3.00"));
        Pnl p = compute(in);
        // revenue = 100 + 30 + 5.99 = 135.99 ; discounts = 10 ; net = 125.99
        assertThat(p.revenue()).isEqualByComparingTo("135.99");
        assertThat(p.discounts()).isEqualByComparingTo("10.00");
        assertThat(p.netRevenue()).isEqualByComparingTo("125.99");
        assertThat(p.cogs()).isEqualByComparingTo("52.00");           // 2×20 + 12
        assertThat(p.paymentFees()).isEqualByComparingTo("3.95");     // 125.99 × 0.029 = 3.65371 + 0.30 = 3.95371 → 3.95
        assertThat(p.returnsReserve()).isEqualByComparingTo("6.30");  // 125.99 × 0.05 = 6.2995 → 6.30
        assertThat(p.shipping()).isEqualByComparingTo("7.50");
        assertThat(p.labour()).isEqualByComparingTo("2.00");
        assertThat(p.packaging()).isEqualByComparingTo("1.25");
        assertThat(p.landedCost()).isEqualByComparingTo("3.00");
        // CM = 125.99 − 52 − 7.50 − 2 − 1.25 − 3.95 − 6.30 − 3 = 49.99
        assertThat(p.contributionMargin()).isEqualByComparingTo("49.99");
        assertThat(p.marginRatio()).isCloseTo(49.99 / 125.99, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(p.meetsMarginFloor(0.12)).isTrue();
        assertThat(p.meetsMarginFloor(0.50)).isFalse();
    }

    @Test
    void negativeMarginWhenCostsExceedRevenue() {
        PnlInput in = PnlInput.of(List.of(PnlLine.of(1, "20.00", "5.00", "18.00")),
            BigDecimal.ZERO, new BigDecimal("9.00"), new BigDecimal("1.00"), new BigDecimal("0.50"), null);
        Pnl p = compute(in);
        // net 15 ; fees 15×0.029+0.30 = 0.735 → 0.74 ; reserve 0.75 ; CM = 15 − 18 − 9 − 1 − 0.5 − 0.74 − 0.75 = −14.99
        assertThat(p.contributionMargin()).isEqualByComparingTo("-14.99");
        assertThat(p.marginRatio()).isLessThan(0);
        assertThat(p.marginRatio()).isCloseTo(-14.99 / 15.0, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(p.meetsMarginFloor(0.0)).isFalse();
    }

    @Test
    void zeroRevenueYieldsZeroRatioAndNoFees() {
        PnlInput in = PnlInput.of(List.of(PnlLine.of(1, "0.00", null, "4.00")), null, new BigDecimal("3.00"), null, null, null);
        Pnl p = compute(in);
        assertThat(p.revenue()).isEqualByComparingTo("0.00");
        assertThat(p.paymentFees()).isEqualByComparingTo("0.00");
        assertThat(p.returnsReserve()).isEqualByComparingTo("0.00");
        assertThat(p.contributionMargin()).isEqualByComparingTo("-7.00");
        assertThat(p.marginRatio()).isEqualTo(0.0);
    }

    @Test
    void fullyDiscountedOrderChargesNoVariableFees() {
        PnlInput in = PnlInput.of(List.of(PnlLine.of(1, "25.00", "25.00", "10.00")), null, null, null, null, null);
        Pnl p = compute(in);
        assertThat(p.netRevenue()).isEqualByComparingTo("0.00");
        assertThat(p.paymentFees()).isEqualByComparingTo("0.00");
        assertThat(p.contributionMargin()).isEqualByComparingTo("-10.00");
        assertThat(p.marginRatio()).isEqualTo(0.0);
    }

    @Test
    void explicitRatesOverrideDefaults() {
        PnlInput in = new PnlInput(List.of(PnlLine.of(1, "100.00", null, "40.00")), null, null, null, null,
            new BigDecimal("0.02"), new BigDecimal("0.00"), new BigDecimal("0.10"), null);
        Pnl p = DefaultOrderPnlCalculator.calculate(in, in.paymentFeeRate(), in.paymentFeeFixed(), in.returnsReserveRate());
        assertThat(p.paymentFees()).isEqualByComparingTo("2.00");
        assertThat(p.returnsReserve()).isEqualByComparingTo("10.00");
        assertThat(p.contributionMargin()).isEqualByComparingTo("48.00");
        assertThat(p.marginRatio()).isEqualTo(0.48);
    }

    @Test
    void emptyOrder() {
        Pnl p = compute(PnlInput.of(List.of(), null, null, null, null, null));
        assertThat(p.revenue()).isEqualByComparingTo("0.00");
        assertThat(p.contributionMargin()).isEqualByComparingTo("0.00");
        assertThat(p.marginRatio()).isEqualTo(0.0);
    }
}
