package ai.intentoms.finance.pnl;

import java.math.BigDecimal;
import java.util.List;

/**
 * Inputs for the order P&L (FR-FIN-04). Null monetary fields mean zero; null rates mean "use the tenant default"
 * ({@code finance.payment_fee_rate} 0.029, {@code finance.payment_fee_fixed} 0.30, {@code finance.returns_reserve_rate} 0.05).
 *
 * @param lines              order lines
 * @param shippingRevenue    shipping charged to the buyer (part of revenue)
 * @param shippingCost       carrier cost of the chosen plan
 * @param labourCost         pick/pack labour for the chosen node(s)
 * @param packagingCost      packaging for the chosen node(s)
 * @param paymentFeeRate     PSP variable fee as a fraction of net revenue, or null for tenant default
 * @param paymentFeeFixed    PSP fixed fee per order, or null for tenant default
 * @param returnsReserveRate expected returns as a fraction of net revenue, or null for tenant default
 * @param landedCost         duty + brokerage + surcharges for cross-border plans (FR-FIN-05)
 */
public record PnlInput(
    List<PnlLine> lines,
    BigDecimal shippingRevenue,
    BigDecimal shippingCost,
    BigDecimal labourCost,
    BigDecimal packagingCost,
    BigDecimal paymentFeeRate,
    BigDecimal paymentFeeFixed,
    BigDecimal returnsReserveRate,
    BigDecimal landedCost
) {
    public PnlInput {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** Builder-style input with every rate left to tenant defaults. */
    public static PnlInput of(List<PnlLine> lines, BigDecimal shippingRevenue, BigDecimal shippingCost, BigDecimal labourCost, BigDecimal packagingCost, BigDecimal landedCost) {
        return new PnlInput(lines, shippingRevenue, shippingCost, labourCost, packagingCost, null, null, null, landedCost);
    }
}
