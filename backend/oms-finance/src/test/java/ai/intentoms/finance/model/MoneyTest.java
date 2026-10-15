package ai.intentoms.finance.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void normalisesScaleWithBankersRounding() {
        assertThat(Money.usd("1.005").amount()).isEqualByComparingTo("1.00");   // half-even rounds to even
        assertThat(Money.usd("1.015").amount()).isEqualByComparingTo("1.02");
        assertThat(Money.usd("1.025").amount()).isEqualByComparingTo("1.02");
        assertThat(Money.usd("3").amount().scale()).isEqualTo(2);
        assertThat(new Money(BigDecimal.TEN, " usd ").currency()).isEqualTo("USD");
    }

    @Test
    void arithmetic() {
        Money a = Money.usd("10.50");
        Money b = Money.usd("2.25");
        assertThat(a.add(b)).isEqualTo(Money.usd("12.75"));
        assertThat(a.subtract(b)).isEqualTo(Money.usd("8.25"));
        assertThat(b.subtract(a)).isEqualTo(Money.usd("-8.25"));
        assertThat(b.subtract(a).isNegative()).isTrue();
        assertThat(a.multiply(new BigDecimal("0.0725"))).isEqualTo(Money.usd("0.76"));
        assertThat(a.multiply(3)).isEqualTo(Money.usd("31.50"));
        assertThat(a.min(b)).isEqualTo(b);
        assertThat(a.max(b)).isEqualTo(a);
        assertThat(a.compareTo(b)).isPositive();
        assertThat(Money.zero("USD").isZero()).isTrue();
        assertThat(a.negate().abs()).isEqualTo(a);
        assertThat(a.ratioTo(Money.usd("42.00"))).isEqualByComparingTo("0.25");
        assertThat(a.ratioTo(Money.zero("USD"))).isEqualByComparingTo("0");
    }

    @Test
    void currencyMismatchIsAProgrammingError() {
        Money usd = Money.usd("1.00");
        Money eur = Money.of("1.00", "EUR");
        assertThatThrownBy(() -> usd.add(eur)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Currency mismatch");
        assertThatThrownBy(() -> usd.subtract(eur)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> usd.min(eur)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> usd.compareTo(eur)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Money(BigDecimal.ONE, "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Money(null, "USD")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void equalityIgnoresInputScale() {
        assertThat(Money.usd("5")).isEqualTo(Money.usd("5.00"));
        assertThat(Money.usd("5").hashCode()).isEqualTo(Money.usd("5.00").hashCode());
    }
}
