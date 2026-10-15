package ai.intentoms.order.model;

import java.time.Instant;
import java.util.List;

/** Shipment as shown on the order view (FR-CS-01); supplied by the orchestration module through {@code ShipmentProvider}. */
public record ShipmentSummary(String id, String nodeId, String carrier, String service, String tracking, String status, List<ShipmentLine> lines,
                              Instant shippedAt, Instant deliveredAt, Instant eta) {
    public record ShipmentLine(String lineId, int qty) {}
}
