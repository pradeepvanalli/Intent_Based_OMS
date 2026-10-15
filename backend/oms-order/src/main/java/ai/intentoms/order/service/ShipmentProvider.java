package ai.intentoms.order.service;

import ai.intentoms.order.model.ShipmentSummary;

import java.util.List;

/**
 * SPI through which the orchestration module supplies shipments for the order view (FR-CS-01). The default (registered by
 * {@code OrderAutoConfiguration} when no other bean exists) returns none.
 */
public interface ShipmentProvider {
    List<ShipmentSummary> shipmentsFor(String orderId);

    static ShipmentProvider none() { return orderId -> List.of(); }
}
