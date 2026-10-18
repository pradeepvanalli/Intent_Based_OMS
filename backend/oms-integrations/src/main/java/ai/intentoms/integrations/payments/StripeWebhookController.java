package ai.intentoms.integrations.payments;

import ai.intentoms.finance.model.Money;
import ai.intentoms.finance.payment.ChargebackService;
import ai.intentoms.finance.payment.PaymentDispute;
import ai.intentoms.integrations.config.HmacSignatures;
import ai.intentoms.integrations.config.InboundWebhookSupport;
import ai.intentoms.integrations.config.IntegrationsConfigKeys;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.error.UnauthorizedException;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.time.TimeSource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code POST /webhooks/payments/stripe?tenant=…} (FD-06): Stripe dispute events become OMS chargebacks through
 * {@link ChargebackService}, matched to the payment by its payment intent (or charge) id. {@code charge.dispute.created}
 * opens one; {@code charge.dispute.closed} records whether it was won or lost; other dispute events update its status.
 * Every other event type is acknowledged and ignored, so the endpoint can be registered for all events.
 *
 * <p>When {@code tenant_config payments.stripe.webhook_secret} is set, a valid {@code Stripe-Signature} (Stripe's
 * {@code t=…,v1=…} scheme, 5-minute tolerance) or {@code X-Hmac-Signature} (the HMAC of the raw body, as the other
 * inbound webhooks use) is mandatory.
 */
@RestController
@RequestMapping("/webhooks/payments/stripe")
public class StripeWebhookController {
    static final String STRIPE_SIGNATURE = "Stripe-Signature";
    static final String HMAC_SIGNATURE = "X-Hmac-Signature";

    private final ChargebackService chargebacks;
    private final InboundWebhookSupport webhooks;
    private final TenantConfigStore config;
    private final TimeSource time;
    private final Metrics metrics;

    public StripeWebhookController(ChargebackService chargebacks, InboundWebhookSupport webhooks, TenantConfigStore config, TimeSource time, Metrics metrics) {
        this.chargebacks = chargebacks;
        this.webhooks = webhooks;
        this.config = config;
        this.time = time;
        this.metrics = metrics;
    }

    @PostMapping
    public Map<String, Object> receive(@RequestBody String rawBody,
                                       @RequestHeader(value = "X-Tenant-Id", required = false) String tenantHeader,
                                       @RequestParam(value = "tenant", required = false) String tenantParam,
                                       @RequestHeader(value = STRIPE_SIGNATURE, required = false) String stripeSignature,
                                       @RequestHeader(value = HMAC_SIGNATURE, required = false) String hmacSignature) {
        return webhooks.run(tenantHeader, tenantParam, "stripe", null, rawBody, null, Set.of("*"), () -> {
            verify(rawBody, stripeSignature, hmacSignature);
            Map<String, Object> event = Json.readMap(rawBody);
            String type = String.valueOf(event.get("type"));
            Map<String, Object> object = object(event);
            if (!type.startsWith("charge.dispute.") || object == null) {
                return Map.of("accepted", true, "ignored", true, "type", type);
            }
            String status = status(type, String.valueOf(object.get("status")));
            String paymentRef = str(object.getOrDefault("payment_intent", object.get("charge")));
            Map<String, Object> result = new LinkedHashMap<>(chargebacks.record(new ChargebackService.Notice("stripe", str(object.get("id")), paymentRef,
                money(object.get("amount"), str(object.get("currency"))), str(object.get("reason")), status)));
            result.put("accepted", true);
            result.put("type", type);
            return result;
        });
    }

    private void verify(String rawBody, String stripeSignature, String hmacSignature) {
        String secret = config.getString(IntegrationsConfigKeys.STRIPE_WEBHOOK_SECRET, null);
        if (secret == null || secret.isBlank()) return;
        boolean ok = (stripeSignature != null && StripeSignatures.matches(secret, rawBody, stripeSignature, time.now(), StripeSignatures.DEFAULT_TOLERANCE_SECONDS))
            || (hmacSignature != null && HmacSignatures.matches(secret, rawBody, hmacSignature));
        if (!ok) {
            metrics.count("oms.integrations.webhook.rejected", "source", "stripe", "reason", stripeSignature == null && hmacSignature == null ? "missing_signature" : "bad_signature");
            throw new UnauthorizedException("Missing or invalid Stripe webhook signature");
        }
    }

    /** OPEN while the dispute is live; WON / LOST once Stripe closes it. */
    static String status(String type, String stripeStatus) {
        String s = stripeStatus == null ? "" : stripeStatus.toLowerCase(Locale.ROOT);
        if ("won".equals(s) || "warning_closed".equals(s)) return PaymentDispute.WON;
        if ("lost".equals(s)) return PaymentDispute.LOST;
        return "charge.dispute.closed".equals(type) ? PaymentDispute.LOST : PaymentDispute.OPEN;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Map<String, Object> event) {
        if (!(event.get("data") instanceof Map<?, ?> data)) return null;
        return data.get("object") instanceof Map<?, ?> o ? (Map<String, Object>) o : null;
    }

    /** Stripe amounts are in the smallest currency unit. */
    private static Money money(Object minorUnits, String currency) {
        if (!(minorUnits instanceof Number n) || currency == null) return null;
        return Money.of(BigDecimal.valueOf(n.longValue()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_EVEN), currency.toUpperCase(Locale.ROOT));
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
