package ai.intentoms.inventory.model;

import java.time.Instant;

/**
 * A quantity of a sku held at a node against ATP (FR-INV-02). Statuses: {@link #ACTIVE} (counts against ATP, whether
 * still a cart/checkout hold or already converted to an order), {@link #RELEASED}, {@link #EXPIRED} (TTL sweep) and
 * {@link #CONSUMED} (shipped: on-hand decremented). Conversion keeps the reservation ACTIVE, sets {@code source=order}
 * and clears {@code expiresAt}; the order id is kept on the row and {@code ReservationService.forRef} matches it.
 *
 * @param source    cart | checkout | order
 * @param refId     cart id, checkout id or order id the hold belongs to
 * @param expiresAt null when the hold has no TTL (orders)
 */
public record Reservation(String id, String sku, String nodeId, int qty, String source, String refId, Instant expiresAt, String status) {
    public static final String ACTIVE = "ACTIVE";
    public static final String RELEASED = "RELEASED";
    public static final String EXPIRED = "EXPIRED";
    public static final String CONSUMED = "CONSUMED";

    public static final String SOURCE_CART = "cart";
    public static final String SOURCE_CHECKOUT = "checkout";
    public static final String SOURCE_ORDER = "order";

    public boolean active() { return ACTIVE.equals(status); }
}
