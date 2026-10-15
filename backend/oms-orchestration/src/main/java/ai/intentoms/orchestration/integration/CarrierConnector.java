package ai.intentoms.orchestration.integration;

import ai.intentoms.orchestration.shipment.ShipmentRecord;

/**
 * SPI towards parcel carriers (UC-05, FR-WF-05): creates a label and returns the tracking number. Delivery scans come
 * back through {@code POST /webhooks/carrier}. The module ships {@link MockCarrierConnector}.
 */
public interface CarrierConnector {
    /** Creates a label for a shipment that has none yet and returns the tracking reference. */
    String createLabel(ShipmentRecord shipment);
}
