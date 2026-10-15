package ai.intentoms.finance.pnl;

import java.math.BigDecimal;

/**
 * One order line for P&L (FR-FIN-04).
 *
 * @param qty       quantity
 * @param unitPrice selling price per unit before discount
 * @param discount  total discount on the line (not per unit); null = 0
 * @param cogs      cost of goods per unit; null = 0
 */
public record PnlLine(int qty, BigDecimal unitPrice, BigDecimal discount, BigDecimal cogs) {
    public PnlLine(int qty, BigDecimal unitPrice, BigDecimal discount, BigDecimal cogs) {
        this.qty = qty;
        this.unitPrice = unitPrice == null ? BigDecimal.ZERO : unitPrice;
        this.discount = discount == null ? BigDecimal.ZERO : discount;
        this.cogs = cogs == null ? BigDecimal.ZERO : cogs;
    }

    public static PnlLine of(int qty, String unitPrice, String discount, String cogs) {
        return new PnlLine(qty, new BigDecimal(unitPrice), discount == null ? null : new BigDecimal(discount), cogs == null ? null : new BigDecimal(cogs));
    }

    /** Gross revenue of the line: qty × unitPrice. */
    public BigDecimal gross() { return unitPrice.multiply(BigDecimal.valueOf(qty)); }

    /** Total COGS of the line: qty × cogs. */
    public BigDecimal totalCogs() { return cogs.multiply(BigDecimal.valueOf(qty)); }
}
