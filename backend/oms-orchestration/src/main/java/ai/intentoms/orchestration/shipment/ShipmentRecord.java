package ai.intentoms.orchestration.shipment;

import ai.intentoms.order.model.ShipmentSummary;

import java.time.Instant;
import java.util.List;

/**
 * A parcel shipment created when the WMS reports {@code shipped} (UC-05). Its lifecycle lives in the state machine
 * entity {@code shipment/<id>} under model {@code shipment.standard}; {@code status} mirrors the dominant state.
 */
public record ShipmentRecord(String id, String orderId, String fulfilmentGroupId, String nodeId, String carrier, String service, String tracking, String status,
                             List<Line> lines, Instant shippedAt, Instant deliveredAt, Instant eta) {
    public record Line(String lineId, String sku, int qty) {}

    public static final String MODEL = "shipment.standard";
    public static final String ENTITY = "shipment";

    public ShipmentSummary summary() {
        return new ShipmentSummary(id, nodeId, carrier, service, tracking, status, lines.stream().map(l -> new ShipmentSummary.ShipmentLine(l.lineId(), l.qty())).toList(),
            shippedAt, deliveredAt, eta);
    }
}
