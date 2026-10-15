package ai.intentoms.orchestration.integration;

import ai.intentoms.platform.json.Json;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * {@code POST /webhooks/carrier} (FR-WF-05, UC-05/UC-08). Body {@code {eventId, type in_transit|out_for_delivery|delivered|
 * exception|returned_to_sender|return_scan, orderId?, tracking, rmaId?}}: shipment state transitions, order-workflow signals
 * ({@code delivered}, {@code carrier_exception}, {@code returned_to_sender}) and return carrier scans. Tenant / signature /
 * idempotency as for the WMS webhook.
 */
@RestController
@RequestMapping("/webhooks/carrier")
public class CarrierWebhookController {
    private final InboundEventService events;
    private final WebhookSecurity security;

    public CarrierWebhookController(InboundEventService events, WebhookSecurity security) {
        this.events = events;
        this.security = security;
    }

    @PostMapping
    public Map<String, Object> receive(@RequestBody String rawBody, @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
                                       @RequestHeader(value = WebhookSecurity.SIGNATURE_HEADER, required = false) String signature) {
        return security.run(tenant, "carrier", rawBody, signature, () -> events.carrier(Json.readMap(rawBody)));
    }
}
