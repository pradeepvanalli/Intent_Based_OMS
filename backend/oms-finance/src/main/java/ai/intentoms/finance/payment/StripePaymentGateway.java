package ai.intentoms.finance.payment;

import ai.intentoms.finance.config.FinanceProperties;
import ai.intentoms.finance.model.Money;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.time.TimeSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Stripe PaymentIntents implementation of {@link PaymentGateway} (FR-FIN-01), active when
 * {@code oms.finance.psp=stripe}. Uses the documented REST shapes:
 * <ul>
 *   <li>authorise: {@code POST /v1/payment_intents} with {@code capture_method=manual}, {@code confirm=true} and the
 *       tokenised {@code payment_method}; approved when the intent reaches {@code requires_capture};</li>
 *   <li>re-authorise: Stripe does not extend a hold in place, so the old intent is cancelled and a new manual-capture
 *       intent is confirmed against the same payment method (read from the old intent);</li>
 *   <li>capture: {@code POST /v1/payment_intents/{id}/capture} with {@code amount_to_capture} (Stripe allows a single
 *       capture per intent and releases the remainder, which matches partial-capture-on-ship);</li>
 *   <li>refund: {@code POST /v1/refunds} with {@code payment_intent} and {@code amount};</li>
 *   <li>void: {@code POST /v1/payment_intents/{id}/cancel}.</li>
 * </ul>
 * Idempotency is delegated to Stripe through the {@code Idempotency-Key} header (payment id, capture id, refund id).
 * Card declines (HTTP 402 {@code card_error}) become result records; every other failure is a
 * {@link PaymentGatewayException}. Bodies are form-encoded as Stripe requires; amounts are sent in minor units.
 * This class is written against the public API contract and is not exercised by the automated test-suite because
 * tests must not require network access or API keys.
 */
@Component
@ConditionalOnProperty(name = "oms.finance.psp", havingValue = "stripe")
public class StripePaymentGateway implements PaymentGateway {
    private static final Logger log = LoggerFactory.getLogger(StripePaymentGateway.class);
    public static final String NAME = "stripe";
    private static final String API_VERSION = "2024-06-20";
    /** Currencies Stripe treats as zero-decimal. */
    private static final Set<String> ZERO_DECIMAL = Set.of("BIF", "CLP", "DJF", "GNF", "JPY", "KMF", "KRW", "MGA", "PYG", "RWF", "UGX", "VND", "VUV", "XAF", "XOF", "XPF");

    private final RestClient http;
    private final FinanceProperties.Stripe cfg;
    private final TimeSource time;

    public StripePaymentGateway(RestClient.Builder builder, FinanceProperties props, TimeSource time) {
        this.cfg = props.stripe();
        this.time = time;
        var factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(cfg.timeout());
        this.http = builder.clone()
            .requestFactory(factory)
            .baseUrl(cfg.baseUrl())
            .defaultHeader("Authorization", "Bearer " + cfg.apiKey())
            .defaultHeader("Stripe-Version", API_VERSION)
            .build();
        if (cfg.apiKey().isBlank()) log.warn("oms.finance.psp=stripe but oms.finance.stripe.api-key is empty; all calls will be rejected by Stripe");
    }

    @Override
    public String name() { return NAME; }

    @Override
    public AuthResult authorize(AuthRequest r) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("amount", minorUnits(r.amount()));
        form.add("currency", r.amount().currency().toLowerCase(Locale.ROOT));
        form.add("payment_method", r.tenderToken());
        form.add("confirm", "true");
        form.add("capture_method", "manual");
        form.add("automatic_payment_methods[enabled]", "true");
        form.add("automatic_payment_methods[allow_redirects]", "never");
        form.add("metadata[order_id]", r.orderId());
        form.add("metadata[payment_id]", r.paymentId());
        form.add("metadata[tender_type]", r.tenderType());
        if (r.agentMandate() != null && !r.agentMandate().isEmpty()) {
            Object mandateId = r.agentMandate().get("mandateId");
            if (mandateId != null) form.add("metadata[agent_mandate_id]", mandateId.toString());
            Object agentId = r.agentMandate().get("agentId");
            if (agentId != null) form.add("metadata[agent_id]", agentId.toString());
        }
        try {
            Map<String, Object> intent = post("/v1/payment_intents", form, r.paymentId());
            return toAuthResult(intent);
        } catch (RestClientResponseException e) {
            return declineOrThrow(e, "authorize");
        } catch (ResourceAccessException e) {
            throw new PaymentGatewayException("Stripe unreachable during authorize: " + e.getMessage(), e);
        }
    }

    @Override
    public AuthResult reauthorize(String pspRef, Money amount) {
        try {
            Map<String, Object> old = get("/v1/payment_intents/" + pspRef);
            String paymentMethod = str(old.get("payment_method"));
            String customer = str(old.get("customer"));
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = old.get("metadata") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            String oldStatus = str(old.get("status"));
            if ("requires_capture".equals(oldStatus)) {
                post("/v1/payment_intents/" + pspRef + "/cancel", new LinkedMultiValueMap<>(), null);
            }
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("amount", minorUnits(amount));
            form.add("currency", amount.currency().toLowerCase(Locale.ROOT));
            form.add("payment_method", paymentMethod);
            if (customer != null) form.add("customer", customer);
            form.add("confirm", "true");
            form.add("capture_method", "manual");
            form.add("off_session", "true");
            form.add("automatic_payment_methods[enabled]", "true");
            form.add("automatic_payment_methods[allow_redirects]", "never");
            metadata.forEach((k, v) -> form.add("metadata[" + k + "]", String.valueOf(v)));
            form.add("metadata[reauthorized_from]", pspRef);
            Map<String, Object> intent = post("/v1/payment_intents", form, "reauth_" + pspRef + "_" + time.now().getEpochSecond() / 60);
            return toAuthResult(intent);
        } catch (RestClientResponseException e) {
            return declineOrThrow(e, "reauthorize");
        } catch (ResourceAccessException e) {
            throw new PaymentGatewayException("Stripe unreachable during reauthorize: " + e.getMessage(), e);
        }
    }

    @Override
    public CaptureResult capture(String pspRef, Money amount, String idempotencyKey) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("amount_to_capture", minorUnits(amount));
        form.add("metadata[capture_id]", idempotencyKey);
        try {
            Map<String, Object> intent = post("/v1/payment_intents/" + pspRef + "/capture", form, idempotencyKey);
            String status = str(intent.get("status"));
            if ("succeeded".equals(status)) {
                String charge = str(intent.get("latest_charge"));
                Money captured = fromMinorUnits(intent.get("amount_received"), amount.currency(), amount);
                return CaptureResult.succeeded(charge != null ? charge : pspRef, captured);
            }
            return CaptureResult.failed("capture_" + status, "PaymentIntent is in status " + status + " after capture");
        } catch (RestClientResponseException e) {
            Map<String, Object> err = error(e);
            String code = str(err.get("code"));
            if (e.getStatusCode().value() == 400 || e.getStatusCode().value() == 402) {
                String mapped = "payment_intent_unexpected_state".equals(code) && String.valueOf(err.get("message")).contains("canceled")
                    ? "auth_expired" : code != null ? code : "capture_failed";
                return CaptureResult.failed(mapped, str(err.get("message")));
            }
            throw new PaymentGatewayException("Stripe capture failed: " + e.getStatusCode() + " " + err.get("message"), e);
        } catch (ResourceAccessException e) {
            throw new PaymentGatewayException("Stripe unreachable during capture: " + e.getMessage(), e);
        }
    }

    @Override
    public RefundResult refund(String pspRef, Money amount, String idempotencyKey, String reason) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("payment_intent", pspRef);
        form.add("amount", minorUnits(amount));
        form.add("metadata[refund_id]", idempotencyKey);
        if (reason != null && !reason.isBlank()) {
            form.add("metadata[reason]", reason.length() > 500 ? reason.substring(0, 500) : reason);
            String stripeReason = stripeRefundReason(reason);
            if (stripeReason != null) form.add("reason", stripeReason);
        }
        try {
            Map<String, Object> refund = post("/v1/refunds", form, idempotencyKey);
            String status = str(refund.get("status"));
            String id = str(refund.get("id"));
            if ("succeeded".equals(status) || "pending".equals(status)) return RefundResult.succeeded(id);
            return RefundResult.failed(str(refund.get("failure_reason")) != null ? str(refund.get("failure_reason")) : "refund_" + status,
                "Refund is in status " + status);
        } catch (RestClientResponseException e) {
            Map<String, Object> err = error(e);
            if (e.getStatusCode().value() == 400 || e.getStatusCode().value() == 402) {
                return RefundResult.failed(str(err.get("code")) != null ? str(err.get("code")) : "refund_failed", str(err.get("message")));
            }
            throw new PaymentGatewayException("Stripe refund failed: " + e.getStatusCode() + " " + err.get("message"), e);
        } catch (ResourceAccessException e) {
            throw new PaymentGatewayException("Stripe unreachable during refund: " + e.getMessage(), e);
        }
    }

    @Override
    public void voidAuth(String pspRef) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("cancellation_reason", "requested_by_customer");
        try {
            post("/v1/payment_intents/" + pspRef + "/cancel", form, null);
        } catch (RestClientResponseException e) {
            Map<String, Object> err = error(e);
            // Already cancelled or already captured: nothing left to void.
            if (e.getStatusCode().value() == 400 && "payment_intent_unexpected_state".equals(str(err.get("code")))) {
                log.info("Stripe void of {} skipped: {}", pspRef, err.get("message"));
                return;
            }
            throw new PaymentGatewayException("Stripe void failed: " + e.getStatusCode() + " " + err.get("message"), e);
        } catch (ResourceAccessException e) {
            throw new PaymentGatewayException("Stripe unreachable during void: " + e.getMessage(), e);
        }
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private AuthResult toAuthResult(Map<String, Object> intent) {
        String status = str(intent.get("status"));
        String id = str(intent.get("id"));
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("payment_intent", id);
        raw.put("status", status);
        raw.put("latest_charge", intent.get("latest_charge"));
        raw.put("payment_method", intent.get("payment_method"));
        if ("requires_capture".equals(status)) {
            return AuthResult.approved(id, time.now().plus(cfg.authTtl()), raw);
        }
        if (intent.get("last_payment_error") instanceof Map<?, ?> err) {
            String code = str(err.get("decline_code")) != null ? str(err.get("decline_code")) : str(err.get("code"));
            return AuthResult.declined(code != null ? code : "card_declined", str(err.get("message")), raw);
        }
        return AuthResult.declined("intent_" + status, "PaymentIntent did not reach requires_capture (status " + status + ")", raw);
    }

    private AuthResult declineOrThrow(RestClientResponseException e, String op) {
        Map<String, Object> err = error(e);
        String type = str(err.get("type"));
        if (e.getStatusCode().value() == 402 || "card_error".equals(type)) {
            String code = str(err.get("decline_code")) != null ? str(err.get("decline_code")) : str(err.get("code"));
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("error_type", type);
            if (err.get("payment_intent") instanceof Map<?, ?> pi) raw.put("payment_intent", pi.get("id"));
            return AuthResult.declined(code != null ? code : "card_declined", str(err.get("message")), raw);
        }
        throw new PaymentGatewayException("Stripe " + op + " failed: " + e.getStatusCode() + " " + err.get("message"), e);
    }

    private Map<String, Object> post(String path, MultiValueMap<String, String> form, String idempotencyKey) {
        var req = http.post().uri(path).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form);
        if (idempotencyKey != null) req = req.header("Idempotency-Key", idempotencyKey);
        String body = req.retrieve().body(String.class);
        return Json.readMap(body);
    }

    private Map<String, Object> get(String path) {
        String body = http.get().uri(path).retrieve().body(String.class);
        return Json.readMap(body);
    }

    private static Map<String, Object> error(RestClientResponseException e) {
        try {
            Map<String, Object> body = Json.readMap(e.getResponseBodyAsString());
            if (body.get("error") instanceof Map<?, ?> err) {
                Map<String, Object> out = new LinkedHashMap<>();
                err.forEach((k, v) -> out.put(String.valueOf(k), v));
                return out;
            }
            return body;
        } catch (RuntimeException parse) {
            return Map.of("message", e.getResponseBodyAsString());
        }
    }

    static String minorUnits(Money m) {
        int exponent = ZERO_DECIMAL.contains(m.currency()) ? 0 : 2;
        return m.amount().movePointRight(exponent).setScale(0, RoundingMode.HALF_EVEN).toPlainString();
    }

    static Money fromMinorUnits(Object minor, String currency, Money fallback) {
        if (!(minor instanceof Number n)) return fallback;
        int exponent = ZERO_DECIMAL.contains(currency) ? 0 : 2;
        return new Money(BigDecimal.valueOf(n.longValue()).movePointLeft(exponent), currency);
    }

    /** Stripe only accepts three enumerated reasons; everything else is kept in metadata. */
    static String stripeRefundReason(String reason) {
        String r = reason.toLowerCase(Locale.ROOT);
        if (r.contains("fraud")) return "fraudulent";
        if (r.contains("duplicate")) return "duplicate";
        if (r.contains("customer") || r.contains("return") || r.contains("cancel")) return "requested_by_customer";
        return null;
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }
}
