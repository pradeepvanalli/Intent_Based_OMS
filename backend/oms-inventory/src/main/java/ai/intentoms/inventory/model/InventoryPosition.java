package ai.intentoms.inventory.model;

import ai.intentoms.platform.error.ValidationException;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * One inventory position at a node (FR-INV-01). A <em>supply</em> row has {@code demandType == null} and a supply type
 * of {@code on_hand}, {@code po}, {@code asn} or {@code in_transit}; a <em>demand</em> row has a demand type of
 * {@code reserved}, {@code allocated} or {@code safety} and represents demand fed by an external system (WMS/POS).
 * Reservations taken through {@code ReservationService} are stored separately and are not positions.
 *
 * @param confidence 0..1 confidence in the figure (FR-INV-05). In feeds a value {@code <= 0} means "unspecified":
 *                   an insert defaults to 1.0 and an update keeps the stored confidence (so a Jev-scored
 *                   confidence survives routine WMS syncs).
 * @param asOf       when the source observed this quantity; feeds older than the stored {@code asOf} are ignored.
 * @param receivedAt optional: when the oldest on-hand unit arrived (IU-03 stock age). When absent, the store sets it the
 *                   moment on-hand goes from zero to positive and keeps it while stock remains.
 * @param supplierId optional: supplier of an inbound (po / asn / in_transit) row, so ATP can weight it by reliability (IU-05).
 */
public record InventoryPosition(String sku, String nodeId, String supplyType, String demandType, int qty, double confidence, Instant asOf,
                                Instant receivedAt, String supplierId) {
    public InventoryPosition(String sku, String nodeId, String supplyType, String demandType, int qty, double confidence, Instant asOf) {
        this(sku, nodeId, supplyType, demandType, qty, confidence, asOf, null, null);
    }

    public static final String ON_HAND = "on_hand";
    public static final String PO = "po";
    public static final String ASN = "asn";
    public static final String IN_TRANSIT = "in_transit";
    public static final String RESERVED = "reserved";
    public static final String ALLOCATED = "allocated";
    public static final String SAFETY = "safety";

    public static final Set<String> SUPPLY_TYPES = Set.of(ON_HAND, PO, ASN, IN_TRANSIT);
    public static final Set<String> DEMAND_TYPES = Set.of(RESERVED, ALLOCATED, SAFETY);

    /** Convenience for an on-hand supply row observed now with unspecified confidence. */
    public static InventoryPosition onHand(String sku, String nodeId, int qty) {
        return new InventoryPosition(sku, nodeId, ON_HAND, null, qty, 0, null);
    }

    public boolean isSupply() { return demandType == null; }

    /** Normalises optional fields (supply type defaults to on_hand, asOf to {@code now}) and validates the value. */
    public InventoryPosition normalised(Instant now) {
        String supply = supplyType == null || supplyType.isBlank() ? ON_HAND : supplyType;
        String demand = demandType == null || demandType.isBlank() ? null : demandType;
        InventoryPosition p = new InventoryPosition(sku, nodeId, supply, demand, qty, confidence, asOf == null ? now : asOf, receivedAt,
            supplierId == null || supplierId.isBlank() ? null : supplierId);
        p.validate();
        return p;
    }

    public void validate() {
        if (sku == null || sku.isBlank()) throw new ValidationException("position.sku is required");
        if (nodeId == null || nodeId.isBlank()) throw new ValidationException("position.nodeId is required", Map.of("sku", sku));
        if (!SUPPLY_TYPES.contains(supplyType))
            throw new ValidationException("Unknown supplyType '" + supplyType + "'", Map.of("sku", sku, "nodeId", nodeId, "allowed", SUPPLY_TYPES));
        if (demandType != null && !DEMAND_TYPES.contains(demandType))
            throw new ValidationException("Unknown demandType '" + demandType + "'", Map.of("sku", sku, "nodeId", nodeId, "allowed", DEMAND_TYPES));
        if (qty < 0) throw new ValidationException("position.qty must be >= 0", Map.of("sku", sku, "nodeId", nodeId, "qty", qty));
        if (confidence > 1.0) throw new ValidationException("position.confidence must be <= 1", Map.of("sku", sku, "nodeId", nodeId));
    }
}
