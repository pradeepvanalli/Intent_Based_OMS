package ai.intentoms.simulation.margin;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/**
 * One order as read from a retailer's CSV export (Margin Replay). Nothing here is persisted: the order exists only for
 * the duration of the replay job.
 *
 * @param orderId         the retailer's order reference (Shopify {@code Name}, generic {@code order_id})
 * @param placedAt        parsed {@code Created at} / {@code placed_at}; null when the file carried none
 * @param shippingCharged shipping revenue the buyer paid (0 when unknown)
 */
public record ImportedOrder(String orderId, Instant placedAt, String email, String customerTier, List<Line> lines, String address1, String city, String state, String zip,
                            String country, String shipMethod, String note, BigDecimal shippingCharged, BigDecimal total, int sourceRow) {
    private static final Pattern PO_BOX = Pattern.compile("(?i)\\b(p\\.?\\s*o\\.?\\s*box|post\\s+office\\s+box|pob)\\b");

    public ImportedOrder {
        lines = lines == null ? List.of() : List.copyOf(lines);
        shippingCharged = shippingCharged == null ? BigDecimal.ZERO : shippingCharged;
    }

    /** @param skuMissing the export had no SKU; {@code sku} is then a synthetic key derived from the item name */
    public record Line(String sku, String name, int qty, BigDecimal unitPrice, boolean skuMissing) {}

    public boolean poBox() { return address1 != null && PO_BOX.matcher(address1).find(); }

    public boolean hasNote() { return note != null && !note.isBlank(); }

    public BigDecimal merchandise() {
        BigDecimal sum = BigDecimal.ZERO;
        for (Line l : lines) sum = sum.add(l.unitPrice().multiply(BigDecimal.valueOf(l.qty())));
        return sum;
    }

    public int units() { int n = 0; for (Line l : lines) n += l.qty(); return n; }
}
