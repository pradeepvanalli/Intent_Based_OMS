package ai.intentoms.inventory.service;

import ai.intentoms.inventory.model.Reservation;
import ai.intentoms.inventory.model.ReservationLine;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Reservations with TTL (FR-INV-02), strongly consistent per sku and node (NFR-06): every reserve runs in a transaction
 * that row-locks the on-hand position so two carts can never both take the last unit.
 */
public interface ReservationService {
    /**
     * Reserves {@code qty} at a node. Throws {@code ConflictException} with code message {@code insufficient_atp} and
     * details {sku, nodeId, available, requested} when ATP (net of active reservations) is below {@code qty}.
     * {@code ttl} is ignored (no expiry) when {@code source} is {@code order}.
     */
    Reservation reserve(String sku, String nodeId, int qty, String source, String refId, Duration ttl);

    /** Atomic multi-line reserve: every line is validated under lock and either all are inserted or none. */
    List<Reservation> reserveAll(List<ReservationLine> lines, String source, String refId, Duration ttl);

    /**
     * As {@link #reserveAll}, but a shortage is returned instead of thrown, so a caller already inside a transaction (a
     * workflow step) is not left rollback-only by an expected outcome and can act on the shortage (re-source).
     */
    ReserveAttempt tryReserveAll(List<ReservationLine> lines, String source, String refId, Duration ttl);

    /** Either the reservations made, or the first shortage {sku, nodeId, available, requested[, lines]} and nothing reserved. */
    record ReserveAttempt(List<Reservation> reserved, Map<String, Object> shortage) {
        public boolean ok() { return shortage == null; }
    }

    /** Cart/checkout -> order at capture: source becomes {@code order}, TTL is cleared, the order id is recorded. Stays ACTIVE. */
    Reservation convert(String reservationId, String orderId);

    /** Gives the quantity back to ATP. Idempotent for already released or expired reservations. */
    Reservation release(String reservationId, String reason);

    /**
     * Fulfilled: decrements on-hand by the reserved quantity and marks the reservation CONSUMED, leaving ATP unchanged.
     * Called by the orchestration on shipment. Idempotent for already consumed reservations.
     */
    Reservation consume(String reservationId);

    Reservation get(String reservationId);

    /** Reservations whose ref id or order id equals {@code refId}, oldest first. */
    List<Reservation> forRef(String refId);

    /** Marks ACTIVE reservations past their TTL EXPIRED and returns how many; run by the scheduled sweep. */
    int expireDue();
}
