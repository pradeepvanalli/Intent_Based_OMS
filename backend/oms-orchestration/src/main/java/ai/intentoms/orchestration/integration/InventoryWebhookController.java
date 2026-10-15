package ai.intentoms.orchestration.integration;

import ai.intentoms.platform.json.Json;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * {@code POST /webhooks/inventory/{nodeId}} (FR-INV-04): WMS / POS stock feed {@code {eventId?, source?, asOf?, positions:[{sku, qty,
 * supplyType?, demandType?, confidence?}]}} (or a bare list). Positions default to the path node and to {@code on_hand}. Lines
 * backordered on the fed skus receive {@code inventory_available}.
 */
@RestController
@RequestMapping("/webhooks/inventory")
public class InventoryWebhookController {
    private final InboundEventService events;
    private final WebhookSecurity security;

    public InventoryWebhookController(InboundEventService events, WebhookSecurity security) {
        this.events = events;
        this.security = security;
    }

    @PostMapping("/{nodeId}")
    public Map<String, Object> feed(@PathVariable String nodeId, @RequestBody String rawBody, @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
                                    @RequestHeader(value = WebhookSecurity.SIGNATURE_HEADER, required = false) String signature) {
        return security.run(tenant, "inventory", rawBody, signature, () -> events.inventory(nodeId, Json.read(rawBody, Object.class)));
    }
}
