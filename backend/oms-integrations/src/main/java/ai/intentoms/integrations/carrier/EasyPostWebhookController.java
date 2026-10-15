package ai.intentoms.integrations.carrier;

import ai.intentoms.integrations.config.InboundWebhookSupport;
import ai.intentoms.integrations.config.IntegrationsConfigKeys;
import ai.intentoms.orchestration.integration.InboundEventService;
import ai.intentoms.platform.json.Json;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

/**
 * {@code POST /webhooks/easypost?tenant=…} (section 11 Carriers P0; UC-05/UC-08). Translates EasyPost tracker webhooks into
 * the OMS carrier events ({@link EasyPostTrackerMapper}) and hands them to {@link InboundEventService#carrier}, so
 * shipment state, the order workflow ({@code delivered}, {@code carrier_exception}, {@code returned_to_sender}) and
 * customer notices progress exactly as with {@code POST /webhooks/carrier}. EasyPost cannot send custom headers, so the
 * tenant comes from the {@code tenant} query parameter configured in the webhook URL; when
 * {@code tenant_config carrier.easypost.webhook_secret} is set the {@code X-Hmac-Signature} header
 * ({@code hmac-sha256-hex=…} over the raw body) is mandatory. Non-tracker events are acknowledged and ignored.
 */
@RestController
@RequestMapping("/webhooks/easypost")
public class EasyPostWebhookController {
    public static final String SIGNATURE_HEADER = "X-Hmac-Signature";
    private final InboundEventService events;
    private final InboundWebhookSupport webhooks;

    public EasyPostWebhookController(InboundEventService events, InboundWebhookSupport webhooks) {
        this.events = events;
        this.webhooks = webhooks;
    }

    @PostMapping
    public Map<String, Object> receive(@RequestBody String rawBody,
                                       @RequestHeader(value = "X-Tenant-Id", required = false) String tenantHeader,
                                       @RequestParam(value = "tenant", required = false) String tenantParam,
                                       @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature) {
        return webhooks.run(tenantHeader, tenantParam, "easypost", IntegrationsConfigKeys.EASYPOST_WEBHOOK_SECRET, rawBody, signature, Set.of("*"), () -> {
            Map<String, Object> event = Json.readMap(rawBody);
            Map<String, Object> carrier = EasyPostTrackerMapper.toCarrierEvent(event);
            if (carrier == null) return Map.of("accepted", true, "ignored", true, "description", String.valueOf(event.get("description")));
            return events.carrier(carrier);
        });
    }
}
