package ai.intentoms.orchestration.integration;

import ai.intentoms.orchestration.shipment.ShipmentRecord;
import org.springframework.stereotype.Component;

import java.util.Locale;

/** Default {@link CarrierConnector}: deterministic tracking numbers derived from the shipment id (no network). */
@Component
public class MockCarrierConnector implements CarrierConnector {
    @Override
    public String createLabel(ShipmentRecord shipment) {
        String carrier = shipment.carrier() == null ? "MOCK" : shipment.carrier().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        String tail = shipment.id().replace("shp_", "").toUpperCase(Locale.ROOT);
        return carrier.substring(0, Math.min(3, carrier.length())) + "-" + tail;
    }
}
