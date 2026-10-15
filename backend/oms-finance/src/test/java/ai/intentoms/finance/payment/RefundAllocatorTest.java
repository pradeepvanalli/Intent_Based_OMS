package ai.intentoms.finance.payment;

import ai.intentoms.finance.model.Money;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FR-RET-04 original-tender proportional split. */
class RefundAllocatorTest {

    @Test
    void splitsInCapturedProportion() {
        List<Money> legs = RefundAllocator.allocateProportionally(List.of(Money.usd("75.00"), Money.usd("25.00")), Money.usd("40.00"));
        assertThat(legs).containsExactly(Money.usd("30.00"), Money.usd("10.00"));
    }

    @Test
    void roundingResidueLandsOnOneTenderAndSumIsExact() {
        List<Money> legs = RefundAllocator.allocateProportionally(List.of(Money.usd("10.00"), Money.usd("10.00"), Money.usd("10.00")), Money.usd("10.00"));
        assertThat(RefundAllocator.sum(legs)).isEqualByComparingTo("10.00");
        assertThat(legs).containsExactlyInAnyOrder(Money.usd("3.34"), Money.usd("3.33"), Money.usd("3.33"));
    }

    @Test
    void clampsToRefundableAndRedistributes() {
        // 60/40 capture, but the first tender has only 5.00 left to refund.
        List<Money> legs = RefundAllocator.allocate(List.of(Money.usd("60.00"), Money.usd("40.00")), List.of(Money.usd("5.00"), Money.usd("40.00")), Money.usd("30.00"));
        assertThat(legs).containsExactly(Money.usd("5.00"), Money.usd("25.00"));
    }

    @Test
    void refusesWhenRefundableIsInsufficient() {
        assertThatThrownBy(() -> RefundAllocator.allocate(List.of(Money.usd("10.00")), List.of(Money.usd("4.00")), Money.usd("5.00")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("below requested");
        assertThatThrownBy(() -> RefundAllocator.allocateProportionally(List.of(Money.usd("10.00")), Money.usd("0.00")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fullRefundReturnsEverything() {
        List<Money> legs = RefundAllocator.allocateProportionally(List.of(Money.usd("19.99"), Money.usd("0.01")), Money.usd("20.00"));
        assertThat(legs).containsExactly(Money.usd("19.99"), Money.usd("0.01"));
    }
}
