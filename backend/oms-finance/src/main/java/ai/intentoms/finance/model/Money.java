package ai.intentoms.finance.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Objects;

/**
 * Immutable monetary amount with an ISO 4217 currency code (FR-FIN-01, FR-FIN-04).
 * Amounts are normalised to scale 2 using {@link RoundingMode#HALF_EVEN} (banker's rounding) so that ledger
 * sums, proportional splits and PSP amounts agree to the cent. Arithmetic between different currencies is a
 * programming error and raises {@link IllegalArgumentException}; the finance module never converts currencies.
 *
 * @param amount   the amount, normalised to scale 2
 * @param currency upper-case ISO 4217 code, e.g. "USD"
 */
public record Money(BigDecimal amount, String currency) implements Comparable<Money> {
    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;

    public Money {
        Objects.requireNonNull(amount, "amount");
        if (currency == null || currency.isBlank()) throw new IllegalArgumentException("currency is required");
        amount = amount.setScale(SCALE, ROUNDING);
        currency = currency.trim().toUpperCase(Locale.ROOT);
    }

    public static Money of(BigDecimal amount, String currency) { return new Money(amount, currency); }
    public static Money of(String amount, String currency) { return new Money(new BigDecimal(amount), currency); }
    public static Money of(double amount, String currency) { return new Money(BigDecimal.valueOf(amount), currency); }
    public static Money usd(String amount) { return of(amount, "USD"); }
    public static Money usd(double amount) { return of(amount, "USD"); }
    public static Money zero(String currency) { return new Money(BigDecimal.ZERO, currency); }

    public Money add(Money other) { return new Money(amount.add(same(other).amount), currency); }
    public Money subtract(Money other) { return new Money(amount.subtract(same(other).amount), currency); }
    public Money multiply(BigDecimal factor) { return new Money(amount.multiply(Objects.requireNonNull(factor, "factor")), currency); }
    public Money multiply(double factor) { return multiply(BigDecimal.valueOf(factor)); }
    public Money multiply(long factor) { return multiply(BigDecimal.valueOf(factor)); }
    public Money negate() { return new Money(amount.negate(), currency); }
    public Money abs() { return isNegative() ? negate() : this; }
    public Money min(Money other) { return compareTo(other) <= 0 ? this : other; }
    public Money max(Money other) { return compareTo(other) >= 0 ? this : other; }

    public boolean isNegative() { return amount.signum() < 0; }
    public boolean isPositive() { return amount.signum() > 0; }
    public boolean isZero() { return amount.signum() == 0; }

    /** True when both amounts are denominated in the same currency. */
    public boolean sameCurrency(Money other) { return other != null && currency.equals(other.currency); }

    /** Fraction {@code this / divisor} as a plain ratio (not money), 10 decimal places; 0 when the divisor is zero. */
    public BigDecimal ratioTo(Money divisor) {
        same(divisor);
        if (divisor.isZero()) return BigDecimal.ZERO;
        return amount.divide(divisor.amount, 10, ROUNDING);
    }

    @Override
    public int compareTo(Money other) { return amount.compareTo(same(other).amount); }

    private Money same(Money other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Currency mismatch: " + currency + " vs " + other.currency);
        }
        return other;
    }

    @Override
    public String toString() { return amount.toPlainString() + " " + currency; }
}
