package ai.intentoms.orchestration.integration;

import ai.intentoms.inventory.model.InventoryPosition;
import ai.intentoms.platform.error.ValidationException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Payload mapping of the WMS / carrier / inventory webhooks (FR-WF-05, FR-INV-04). */
class WebhookPayloadsTest {

    @Test
    void wmsShippedBecomesSignalPayload() {
        var ev = WebhookPayloads.wms("DC-NJ", Map.of("eventId", "wms-1", "type", "shipped", "orderId", "ord_1", "fulfilmentGroupId", "fg_1",
            "lines", List.of(Map.of("lineId", "1", "qty", 2), Map.of("lineId", "2", "qty", "1")), "carrier", "UPS", "service", "ground", "tracking", "1Z999"));
        assertThat(ev.forReturn()).isFalse();
        Map<String, Object> p = ev.signalPayload();
        assertThat(p).containsEntry("eventId", "wms-1").containsEntry("type", "shipped").containsEntry("nodeId", "DC-NJ").containsEntry("fulfilmentGroupId", "fg_1")
            .containsEntry("carrier", "UPS").containsEntry("service", "ground").containsEntry("tracking", "1Z999");
        assertThat(p.get("lines")).isEqualTo(List.of(Map.of("lineId", "1", "qty", 2), Map.of("lineId", "2", "qty", 1)));
    }

    @Test
    void wmsEventIdIsGeneratedWhenAbsentAndTypeIsNormalised() {
        var ev = WebhookPayloads.wms("ST-CHI-01", Map.of("type", "Store_Rejected", "orderId", "ord_1", "reason", "no stock"));
        assertThat(ev.eventId()).startsWith("wms:");
        assertThat(ev.type()).isEqualTo("store_rejected");
        assertThat(ev.signalPayload()).containsEntry("reason", "no stock").doesNotContainKey("lines");
    }

    @Test
    void wmsReturnEventsNeedAnRma() {
        assertThatThrownBy(() -> WebhookPayloads.wms("DC-NJ", Map.of("type", "received"))).isInstanceOf(ValidationException.class).hasMessageContaining("rmaId");
        var ev = WebhookPayloads.wms("DC-NJ", Map.of("type", "received", "rmaId", "rma_1"));
        assertThat(ev.forReturn()).isTrue();
        assertThatThrownBy(() -> WebhookPayloads.wms("DC-NJ", Map.of("type", "shipped"))).isInstanceOf(ValidationException.class).hasMessageContaining("orderId");
        assertThatThrownBy(() -> WebhookPayloads.wms("DC-NJ", Map.of("type", "teleported", "orderId", "o"))).isInstanceOf(ValidationException.class);
    }

    @Test
    void carrierEventsMapToWorkflowSignals() {
        assertThat(WebhookPayloads.carrier(Map.of("eventId", "c1", "type", "delivered", "tracking", "1Z")).signal()).isEqualTo("delivered");
        assertThat(WebhookPayloads.carrier(Map.of("type", "exception", "tracking", "1Z")).signal()).isEqualTo("carrier_exception");
        assertThat(WebhookPayloads.carrier(Map.of("type", "in_transit", "orderId", "ord_1")).signal()).isNull();
        assertThat(WebhookPayloads.carrier(Map.of("type", "return_scan", "rmaId", "rma_1")).rmaId()).isEqualTo("rma_1");
        assertThatThrownBy(() -> WebhookPayloads.carrier(Map.of("type", "return_scan"))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> WebhookPayloads.carrier(Map.of("type", "delivered"))).isInstanceOf(ValidationException.class).hasMessageContaining("tracking");
        assertThat(WebhookPayloads.carrier(Map.of("type", "delivered", "tracking", "1Z", "shipmentId", "shp_1")).signalPayload()).containsEntry("shipmentId", "shp_1").containsEntry("tracking", "1Z");
    }

    @Test
    void inventoryFeedDefaultsNodeAndSupplyType() {
        Instant now = Instant.parse("2026-10-05T10:00:00Z");
        List<InventoryPosition> ps = WebhookPayloads.positions("DC-TX", Map.of("source", "wms", "positions", List.of(Map.of("sku", "TEE-BLK-M", "qty", 12), Map.of("sku", "CAP-NVY", "qty", 3, "nodeId", "DC-NJ", "supplyType", "asn"))), now);
        assertThat(ps).hasSize(2);
        assertThat(ps.get(0).nodeId()).isEqualTo("DC-TX");
        assertThat(ps.get(0).supplyType()).isEqualTo(InventoryPosition.ON_HAND);
        assertThat(ps.get(0).asOf()).isEqualTo(now);
        assertThat(ps.get(1).nodeId()).isEqualTo("DC-NJ");
        assertThat(ps.get(1).supplyType()).isEqualTo(InventoryPosition.ASN);
        assertThat(WebhookPayloads.positions("DC-TX", List.of(Map.of("sku", "X", "qty", 1)), now)).hasSize(1);
        assertThatThrownBy(() -> WebhookPayloads.positions("DC-TX", Map.of("positions", List.of()), now)).isInstanceOf(ValidationException.class);
    }

    @Test
    void hmacSignatureAcceptsHexAndBase64() {
        String body = "{\"eventId\":\"x\"}";
        String hex = WebhookSecurity.hmacHex("s3cret", body);
        assertThat(hex).hasSize(64);
        assertThat(java.util.Base64.getEncoder().encodeToString(WebhookSecurity.hmac("s3cret", body))).isNotEqualTo(hex);
    }
}
