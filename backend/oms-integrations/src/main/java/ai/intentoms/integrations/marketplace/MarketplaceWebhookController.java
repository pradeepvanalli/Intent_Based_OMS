package ai.intentoms.integrations.marketplace;

import ai.intentoms.integrations.config.InboundWebhookSupport;
import ai.intentoms.integrations.config.IntegrationsConfigKeys;
import ai.intentoms.order.service.OrderService;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.json.Json;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code POST /webhooks/marketplace/{marketplace}} (FR-CAP-01; section 11 "P0: Shopify + one marketplace"). Accepts an
 * Amazon SP-API–shaped order or the generic shape (see {@link MarketplaceOrderMapper}), captures it as channel
 * {@code marketplace} and answers {@code 201 {orderId, channelOrderId, replayed:false}} or {@code 200 {..., replayed:true}}
 * on a replay of the same marketplace order id (FR-CAP-04). Public path: the tenant comes from {@code X-Tenant-Id} (or
 * {@code ?tenant=}) and, when {@code tenant_config marketplace.<name>.webhook_secret} is set, {@code X-Marketplace-Signature}
 * must be HMAC-SHA256(secret, raw body) in hex or base64.
 */
@RestController
@RequestMapping("/webhooks/marketplace")
public class MarketplaceWebhookController {
    public static final String SIGNATURE_HEADER = "X-Marketplace-Signature";
    private static final Pattern NAME = Pattern.compile("^[a-z0-9_-]{2,32}$");
    private final MarketplaceIngestService ingest;
    private final InboundWebhookSupport webhooks;

    public MarketplaceWebhookController(MarketplaceIngestService ingest, InboundWebhookSupport webhooks) {
        this.ingest = ingest;
        this.webhooks = webhooks;
    }

    @PostMapping("/{marketplace}")
    public ResponseEntity<Map<String, Object>> receive(@PathVariable String marketplace, @RequestBody String rawBody,
                                                       @RequestHeader(value = "X-Tenant-Id", required = false) String tenantHeader,
                                                       @RequestParam(value = "tenant", required = false) String tenantParam,
                                                       @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature) {
        String name = marketplace == null ? "" : marketplace.trim().toLowerCase(Locale.ROOT);
        if (!NAME.matcher(name).matches()) throw new ValidationException("Invalid marketplace name '" + marketplace + "'");
        return webhooks.run(tenantHeader, tenantParam, "marketplace-" + name, IntegrationsConfigKeys.marketplaceWebhookSecret(name), rawBody, signature,
            Set.of("order.create", "order.read"), () -> {
                OrderService.CaptureResult r = ingest.ingest(name, Json.readMap(rawBody));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("orderId", r.order().id());
                out.put("channel", r.order().channel());
                out.put("channelOrderId", r.order().channelOrderId());
                out.put("status", r.order().status());
                out.put("replayed", r.replayed());
                return ResponseEntity.status(r.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(out);
            });
    }
}
