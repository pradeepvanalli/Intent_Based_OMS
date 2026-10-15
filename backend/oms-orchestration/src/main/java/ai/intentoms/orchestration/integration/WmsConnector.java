package ai.intentoms.orchestration.integration;

import ai.intentoms.order.model.FulfilmentGroup;
import ai.intentoms.order.model.Order;

import java.util.Map;

/**
 * SPI towards the warehouse / store fulfilment system (FR-WF-05, UC-03). The order workflow hands every released
 * fulfilment group to the connector and asks it to cancel a release when the order is cancelled before shipment.
 * The module ships {@link MockWmsConnector}; a real adapter replaces it by registering its own bean.
 */
public interface WmsConnector {
    /** Sends a release (pick/pack/ship request) for one fulfilment group; returns a JSON-friendly acknowledgement. */
    Map<String, Object> sendRelease(Order order, FulfilmentGroup group);

    /** Cancels a release that was sent earlier; idempotent. */
    Map<String, Object> cancelRelease(Order order, FulfilmentGroup group, String reason);
}
