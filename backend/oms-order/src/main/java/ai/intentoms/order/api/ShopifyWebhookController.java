package ai.intentoms.order.api;

import ai.intentoms.order.capture.CaptureRequest;
import ai.intentoms.order.capture.ShopifyOrderMapper;
import ai.intentoms.order.config.OrderConfigKeys;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.service.OrderService;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.error.UnauthorizedException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.security.Principal;
import ai.intentoms.platform.security.PrincipalAuthentication;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;

/**
 * Shopify {@code orders/create} webhook (FR-CAP-01 storefront connector). The path is public ({@code /webhooks/**}) so the
 * request authenticates itself: when {@code tenant_config shopify.webhook_secret} is set the {@code X-Shopify-Hmac-Sha256}
 * header must equal base64(HMAC-SHA256(secret, raw body)). Tenant comes from {@code X-Tenant-Id} or {@code ?tenant=}.
 * Replays of the same Shopify order id return the existing order (FR-CAP-04); the webhook id doubles as Idempotency-Key.
 */
@RestController
@RequestMapping("/webhooks/shopify")
public class ShopifyWebhookController {
    private static final Logger log = LoggerFactory.getLogger(ShopifyWebhookController.class);
    private final OrderService orders;
    private final TenantConfigStore config;
    private final AuthorizationService authz;
    private final Metrics metrics;

    public ShopifyWebhookController(OrderService orders, TenantConfigStore config, AuthorizationService authz, Metrics metrics) {
        this.orders = orders;
        this.config = config;
        this.authz = authz;
        this.metrics = metrics;
    }

    @PostMapping
    public ResponseEntity<Order> orderCreated(@RequestBody String rawBody,
                                              @RequestHeader(value = "X-Shopify-Hmac-Sha256", required = false) String hmac,
                                              @RequestHeader(value = "X-Shopify-Webhook-Id", required = false) String webhookId,
                                              @RequestHeader(value = "X-Shopify-Topic", required = false) String topic,
                                              @RequestHeader(value = "X-Tenant-Id", required = false) String tenantHeader,
                                              @RequestParam(value = "tenant", required = false) String tenantParam) {
        String tenant = tenantHeader != null && !tenantHeader.isBlank() ? tenantHeader : tenantParam;
        if (tenant == null || tenant.isBlank()) tenant = TenantContext.current().orElseThrow(() -> new ValidationException("Tenant required: send X-Tenant-Id or ?tenant="));
        String finalTenant = tenant;
        return TenantContext.supplyAs(tenant, () -> {
            verify(rawBody, hmac);
            if (topic != null && !topic.startsWith("orders/")) {
                log.info("Ignoring Shopify topic {}", topic);
                return ResponseEntity.noContent().build();
            }
            Map<String, Object> body = Json.readMap(rawBody);
            CaptureRequest req = ShopifyOrderMapper.map(body);
            boolean installedPrincipal = authz.currentPrincipal().isEmpty();
            if (installedPrincipal) SecurityContextHolder.getContext().setAuthentication(new PrincipalAuthentication(new Principal(Principal.Type.SYSTEM, "shopify-webhook", finalTenant, java.util.Set.of("integration"), java.util.Set.of("order.create", "order.read"), 3)));
            try {
                OrderService.CaptureResult r = orders.captureDetailed(req, webhookId == null ? null : "shopify:" + webhookId);
                metrics.count("oms.order.webhook", "source", "shopify", "replay", String.valueOf(r.replayed()));
                return ResponseEntity.status(r.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(r.order());
            } finally {
                if (installedPrincipal) SecurityContextHolder.clearContext();
            }
        });
    }

    /** Constant-time HMAC check; required whenever the tenant has a secret configured. */
    void verify(String rawBody, String hmacHeader) {
        String secret = config.getString(OrderConfigKeys.SHOPIFY_WEBHOOK_SECRET, null);
        if (secret == null || secret.isBlank()) return;
        if (hmacHeader == null || hmacHeader.isBlank()) throw new UnauthorizedException("Missing X-Shopify-Hmac-Sha256");
        String expected = hmac(secret, rawBody);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), hmacHeader.trim().getBytes(StandardCharsets.UTF_8))) {
            metrics.count("oms.order.webhook.rejected", "source", "shopify");
            throw new UnauthorizedException("Invalid Shopify webhook signature");
        }
    }

    public static String hmac(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
