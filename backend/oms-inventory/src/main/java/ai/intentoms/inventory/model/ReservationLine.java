package ai.intentoms.inventory.model;

import ai.intentoms.platform.error.ValidationException;

import java.util.Comparator;
import java.util.Map;

/** One line of an atomic multi-line reservation (FR-INV-02). */
public record ReservationLine(String sku, String nodeId, int qty) {
    /** Deterministic lock order (sku, node) so concurrent {@code reserveAll} calls never deadlock (NFR-06). */
    public static final Comparator<ReservationLine> LOCK_ORDER = Comparator.comparing(ReservationLine::sku).thenComparing(ReservationLine::nodeId);

    public void validate() {
        if (sku == null || sku.isBlank()) throw new ValidationException("reservation.sku is required");
        if (nodeId == null || nodeId.isBlank()) throw new ValidationException("reservation.nodeId is required", Map.of("sku", sku));
        if (qty <= 0) throw new ValidationException("reservation.qty must be > 0", Map.of("sku", sku, "nodeId", nodeId, "qty", qty));
    }

    public String key() { return sku + "|" + nodeId; }
}
