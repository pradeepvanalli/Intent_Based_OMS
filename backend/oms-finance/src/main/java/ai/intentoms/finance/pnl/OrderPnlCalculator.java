package ai.intentoms.finance.pnl;

/**
 * Order P&L at every decision point (FR-FIN-04): revenue, discounts, COGS, shipping, labour, packaging, payment fees,
 * returns reserve and landed cost → contribution margin, so sourcing can enforce the margin floor (FR-SRC-04, GR-01).
 */
public interface OrderPnlCalculator {
    Pnl compute(PnlInput in);
}
