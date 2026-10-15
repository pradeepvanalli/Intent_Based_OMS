package ai.intentoms.finance.pnl;

import java.math.BigDecimal;

/**
 * Order contribution P&L (FR-FIN-04), all amounts at scale 2.
 * {@code contributionMargin = revenue − discounts − cogs − shipping − labour − packaging − paymentFees − returnsReserve − landedCost};
 * {@code marginRatio = contributionMargin / (revenue − discounts)}, 0 when net revenue is 0.
 *
 * @param revenue        gross merchandise revenue plus shipping revenue
 * @param discounts      total discounts
 * @param cogs           cost of goods sold
 * @param shipping       carrier cost
 * @param labour         pick/pack labour cost
 * @param packaging      packaging cost
 * @param paymentFees    PSP fees (rate × net revenue + fixed)
 * @param returnsReserve expected returns (rate × net revenue)
 * @param landedCost     duty, brokerage and surcharges
 * @param contributionMargin net contribution
 * @param marginRatio    contribution margin over net revenue (4 dp)
 */
public record Pnl(
    BigDecimal revenue,
    BigDecimal discounts,
    BigDecimal cogs,
    BigDecimal shipping,
    BigDecimal labour,
    BigDecimal packaging,
    BigDecimal paymentFees,
    BigDecimal returnsReserve,
    BigDecimal landedCost,
    BigDecimal contributionMargin,
    double marginRatio
) {
    /** Net revenue after discounts. */
    public BigDecimal netRevenue() { return revenue.subtract(discounts); }

    /** GR-01 / FR-SRC-04 helper: whether this plan clears the tenant's contribution-margin floor (a ratio such as 0.12). */
    public boolean meetsMarginFloor(double floor) { return marginRatio >= floor; }
}
