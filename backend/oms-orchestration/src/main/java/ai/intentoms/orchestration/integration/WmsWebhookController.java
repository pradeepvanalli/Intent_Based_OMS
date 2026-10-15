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
 * {@code POST /webhooks/wms/{nodeId}} (FR-INV-04, FR-WF-05; scripts/demo.sh step 5). Body
 * {@code {eventId, type shipped|short_pick|store_rejected|pick_started|packed|picked|staged_for_pickup|picked_up|received|inspected,
 * orderId, fulfilmentGroupId?, lines?[{lineId, qty}], carrier?, service?, tracking?, rmaId?}}; tenant from {@code X-Tenant-Id};
 * HMAC in {@code X-Webhook-Signature} when the tenant configured {@code webhooks.inbound_secret}; idempotent on {@code eventId}.
 */
@RestController
@RequestMapping("/webhooks/wms")
public class WmsWebhookController {
    private final InboundEventService events;
    private final WebhookSecurity security;

    public WmsWebhookController(InboundEventService events, WebhookSecurity security) {
        this.events = events;
        this.security = security;
    }

    @PostMapping("/{nodeId}")
    public Map<String, Object> receive(@PathVariable String nodeId, @RequestBody String rawBody,
                                       @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
                                       @RequestHeader(value = WebhookSecurity.SIGNATURE_HEADER, required = false) String signature) {
        return security.run(tenant, "wms", rawBody, signature, () -> events.wms(nodeId, Json.readMap(rawBody)));
    }
}
