package ai.intentoms.finance.payment;

import ai.intentoms.finance.model.Money;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure arithmetic for splitting a refund across the original tenders (FR-RET-04 "original tender split").
 * Each tender receives {@code amount × captured_i / Σ captured}, rounded to the cent (HALF_EVEN); rounding residue is
 * settled on the tender with the largest remaining refundable balance, and no tender ever receives more than its
 * refundable balance (captured minus already refunded). Any shortfall caused by clamping is redistributed to tenders
 * that still have room, so the legs always sum to {@code amount} whenever Σ refundable ≥ amount.
 */
public final class RefundAllocator {
    private RefundAllocator() {}

    /**
     * @param weights    per-tender original captured amounts (proportion basis)
     * @param refundable per-tender remaining refundable balance (same order as {@code weights})
     * @param amount     total to refund
     * @return per-tender amounts (same order), summing to {@code amount}
     * @throws IllegalArgumentException when Σ refundable &lt; amount or the inputs are inconsistent
     */
    public static List<Money> allocate(List<Money> weights, List<Money> refundable, Money amount) {
        if (weights.size() != refundable.size()) throw new IllegalArgumentException("weights and refundable must align");
        if (amount.isNegative() || amount.isZero()) throw new IllegalArgumentException("refund amount must be positive");
        String ccy = amount.currency();
        Money totalRefundable = Money.zero(ccy);
        Money totalWeight = Money.zero(ccy);
        for (int i = 0; i < weights.size(); i++) {
            totalRefundable = totalRefundable.add(refundable.get(i));
            totalWeight = totalWeight.add(weights.get(i));
        }
        if (totalRefundable.compareTo(amount) < 0) {
            throw new IllegalArgumentException("Refundable balance " + totalRefundable + " is below requested " + amount);
        }
        List<Money> out = new ArrayList<>(weights.size());
        Money allocated = Money.zero(ccy);
        for (int i = 0; i < weights.size(); i++) {
            Money share = totalWeight.isZero()
                ? Money.zero(ccy)
                : amount.multiply(weights.get(i).ratioTo(totalWeight));
            share = share.min(refundable.get(i));
            if (share.isNegative()) share = Money.zero(ccy);
            out.add(share);
            allocated = allocated.add(share);
        }
        // Settle rounding residue and clamping shortfall on tenders that still have room, largest headroom first.
        Money remaining = amount.subtract(allocated);
        while (remaining.isPositive()) {
            int best = -1;
            Money bestRoom = Money.zero(ccy);
            for (int i = 0; i < out.size(); i++) {
                Money room = refundable.get(i).subtract(out.get(i));
                if (room.compareTo(bestRoom) > 0) { best = i; bestRoom = room; }
            }
            if (best < 0) throw new IllegalStateException("No tender has room for remaining " + remaining);
            Money add = remaining.min(bestRoom);
            out.set(best, out.get(best).add(add));
            remaining = remaining.subtract(add);
        }
        // Over-allocation can only arise from rounding up; trim from the largest leg.
        while (remaining.isNegative()) {
            int largest = 0;
            for (int i = 1; i < out.size(); i++) if (out.get(i).compareTo(out.get(largest)) > 0) largest = i;
            Money trim = remaining.negate().min(out.get(largest));
            out.set(largest, out.get(largest).subtract(trim));
            remaining = remaining.add(trim);
        }
        return out;
    }

    /** Convenience for the common case where no refunds have been issued yet (refundable == captured). */
    public static List<Money> allocateProportionally(List<Money> captured, Money amount) {
        return allocate(captured, captured, amount);
    }

    static BigDecimal sum(List<Money> amounts) {
        BigDecimal s = BigDecimal.ZERO;
        for (Money m : amounts) s = s.add(m.amount());
        return s;
    }
}
