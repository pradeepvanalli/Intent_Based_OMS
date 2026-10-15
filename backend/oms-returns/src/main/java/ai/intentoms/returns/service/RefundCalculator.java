package ai.intentoms.returns.service;

import ai.intentoms.finance.model.Money;
import ai.intentoms.order.model.Charge;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.model.OrderLine;
import ai.intentoms.returns.model.RefundInfo;
import ai.intentoms.returns.model.ReturnLine;

import java.math.BigDecimal;
import java.util.List;

/**
 * Refund arithmetic (FR-RET-04): merchandise of the returned units, plus the proportional tax share of each order line,
 * plus (when the tenant refunds shipping) the shipping charge in proportion to the merchandise returned, minus a
 * restocking fee when the inspected condition is one the tenant charges for. Money uses banker's rounding to the cent
 * via {@link Money}. Pure; the service supplies the tenant settings.
 */
public final class RefundCalculator {
    private RefundCalculator() {}

    /**
     * @param lines               returned lines (unit price, qty, tax share already computed by {@link #taxShare})
     * @param order               the order (shipping charges, merchandise total)
     * @param restockingFeePct    fraction withheld from merchandise when the fee applies (0 for none)
     * @param feeApplies          whether the inspected condition is in the tenant's restocking-fee conditions
     * @param refundShipping      tenant setting {@code returns.refund_shipping}
     * @param method              {@code original_tender} or {@code store_credit}
     */
    public static RefundInfo compute(List<ReturnLine> lines, Order order, double restockingFeePct, boolean feeApplies, boolean refundShipping, String method) {
        String currency = order.currency() == null ? "USD" : order.currency();
        Money merchandise = Money.zero(currency);
        Money tax = Money.zero(currency);
        for (ReturnLine l : lines) {
            merchandise = merchandise.add(l.extended());
            if (l.tax() != null) tax = tax.add(l.tax());
        }
        Money fee = feeApplies && restockingFeePct > 0 ? merchandise.multiply(BigDecimal.valueOf(restockingFeePct)) : Money.zero(currency);
        Money shipping = Money.zero(currency);
        if (refundShipping) {
            Money paidShipping = Money.zero(currency);
            if (order.charges() != null) for (Charge c : order.charges()) if ("shipping".equals(c.type()) && c.amount() != null && c.amount().isPositive()) paidShipping = paidShipping.add(c.amount());
            Money orderMerch = order.merchandiseTotal();
            if (paidShipping.isPositive() && orderMerch.isPositive()) {
                BigDecimal ratio = merchandise.ratioTo(orderMerch).min(BigDecimal.ONE);
                shipping = paidShipping.multiply(ratio);
            }
        }
        Money total = merchandise.add(tax).add(shipping).subtract(fee).max(Money.zero(currency));
        return new RefundInfo(total.amount(), currency, fee.amount(), tax.amount(), shipping.amount(), method, List.of(), RefundInfo.PENDING, null, null, null, null);
    }

    /** Tax share of {@code qty} units out of an order line (proportional to quantity). */
    public static Money taxShare(OrderLine line, int qty) {
        String currency = line.unitPrice().currency();
        if (line.tax() == null || line.tax().isZero() || line.qty() <= 0) return Money.zero(currency);
        return line.tax().multiply(BigDecimal.valueOf(qty).divide(BigDecimal.valueOf(line.qty()), 10, Money.ROUNDING));
    }
}
