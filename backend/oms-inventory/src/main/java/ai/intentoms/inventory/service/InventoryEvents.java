package ai.intentoms.inventory.service;

/**
 * Ledger event types written by this module. Reservation events live on aggregate {@link #RESERVATION} (id = reservation id);
 * stock events live on aggregate {@link #NODE} (id = node id). Payload keys are documented per constant.
 */
public final class InventoryEvents {
    private InventoryEvents() {}

    public static final String RESERVATION = "reservation";
    public static final String NODE = "node";

    /** {reservationId, sku, nodeId, qty, source, refId, expiresAt?} */
    public static final String RESERVATION_CREATED = "reservation.created";
    /** {reservationId, sku, nodeId, qty, source=order, refId, orderId} */
    public static final String RESERVATION_CONVERTED = "reservation.converted";
    /** {reservationId, sku, nodeId, qty, source, refId, orderId?, reason} */
    public static final String RESERVATION_RELEASED = "reservation.released";
    /** {reservationId, sku, nodeId, qty, source, refId, expiresAt} */
    public static final String RESERVATION_EXPIRED = "reservation.expired";
    /** {reservationId, sku, nodeId, qty, source, refId, orderId?, onHandAfter} */
    public static final String RESERVATION_CONSUMED = "reservation.consumed";

    /** {nodeId, source, positions, skus} */
    public static final String INVENTORY_SYNCED = "inventory.synced";
    /** {nodeId, sku, delta, onHandAfter, reason} */
    public static final String INVENTORY_ADJUSTED = "inventory.adjusted";
    /** {nodeId, tolerance, drifted:[{sku, nodeId, expected, actual, delta}], syncLogId} */
    public static final String INVENTORY_DRIFT_DETECTED = "inventory.drift_detected";
    /** {nodeId, sku, confidence} */
    public static final String INVENTORY_CONFIDENCE_UPDATED = "inventory.confidence_updated";
}
