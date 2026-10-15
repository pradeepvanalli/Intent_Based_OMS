package com.example.oms.extensions;

import ai.intentoms.finance.model.Money;
import ai.intentoms.finance.payment.AuthRequest;
import ai.intentoms.finance.payment.AuthResult;
import ai.intentoms.finance.payment.CaptureResult;
import ai.intentoms.finance.payment.PaymentGateway;
import ai.intentoms.finance.payment.PaymentGatewayException;
import ai.intentoms.finance.payment.RefundResult;
import ai.intentoms.platform.time.TimeSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.Map;

/**
 * Example {@link PaymentGateway} for a PSP of your choice (FR-FIN-01). Selected with {@code oms.finance.psp=acme}
 * (the mock gateway is active when the property is missing or {@code mock}; Stripe when {@code stripe}).
 *
 * <p>Contract (see the interface Javadoc): translate <em>business</em> outcomes (declines, expired authorisations,
 * rejected refunds) into result records so {@code PaymentService} can persist them and emit {@code payment.failed} /
 * {@code refund.failed}; throw {@link PaymentGatewayException} only for transport-level faults. Idempotency keys
 * passed to {@link #capture} and {@link #refund} are OMS ids: forward them to the PSP so a retried call after a crash
 * never double-charges. Tender tokens are PSP payment-method tokens, never PANs (PCI scope, docs/COMPLIANCE.md).
 */
@Component
@ConditionalOnProperty(name = "oms.finance.psp", havingValue = "acme")
public class CustomPaymentGateway implements PaymentGateway {
    public static final String NAME = "acme";
    private final RestClient http;
    private final TimeSource time;

    public CustomPaymentGateway(RestClient.Builder builder, TimeSource time, org.springframework.core.env.Environment env) {
        this.time = time;
        this.http = builder.baseUrl(env.getProperty("example.psp.base-url", "https://psp.example.com"))
            .defaultHeader("Authorization", "Bearer " + env.getProperty("example.psp.api-key", ""))
            .build();
    }

    @Override public String name() { return NAME; }

    @Override
    public AuthResult authorize(AuthRequest r) {
        try {
            Map<?, ?> res = http.post().uri("/v1/authorizations")
                .header("Idempotency-Key", r.paymentId())
                .body(Map.of("amount", minor(r.amount()), "currency", r.amount().currency(), "token", r.tenderToken(),
                             "metadata", Map.of("orderId", r.orderId(), "tenderType", r.tenderType(), "agentMandate", r.agentMandate() == null ? Map.of() : r.agentMandate())))
                .retrieve().body(Map.class);
            if (res == null) throw new PaymentGatewayException(NAME + " returned an empty body");
            if ("approved".equals(res.get("status"))) {
                return AuthResult.approved(String.valueOf(res.get("id")), time.now().plus(Duration.ofDays(7)), Map.of("network", String.valueOf(res.get("network"))));
            }
            return AuthResult.declined(String.valueOf(res.getOrDefault("decline_code", "card_declined")), String.valueOf(res.get("message")), Map.of());
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 402) return AuthResult.declined("card_declined", e.getResponseBodyAsString(), Map.of());
            throw new PaymentGatewayException(NAME + " authorize failed: HTTP " + e.getStatusCode().value(), e);
        }
    }

    @Override
    public AuthResult reauthorize(String pspRef, Money amount) {
        try {
            Map<?, ?> res = http.post().uri("/v1/authorizations/{id}/extend", pspRef).body(Map.of("amount", minor(amount))).retrieve().body(Map.class);
            return AuthResult.approved(String.valueOf(res == null ? pspRef : res.getOrDefault("id", pspRef)), time.now().plus(Duration.ofDays(7)), Map.of("previous_ref", pspRef));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 402) return AuthResult.declined("reauth_declined", e.getResponseBodyAsString(), Map.of());
            throw new PaymentGatewayException(NAME + " reauthorize failed", e);
        }
    }

    @Override
    public CaptureResult capture(String pspRef, Money amount, String idempotencyKey) {
        try {
            Map<?, ?> res = http.post().uri("/v1/authorizations/{id}/capture", pspRef)
                .header("Idempotency-Key", idempotencyKey)
                .body(Map.of("amount", minor(amount))).retrieve().body(Map.class);
            if (res != null && "captured".equals(res.get("status"))) return CaptureResult.succeeded(String.valueOf(res.get("id")), amount);
            return CaptureResult.failed(String.valueOf(res == null ? "unknown" : res.getOrDefault("code", "capture_failed")), String.valueOf(res == null ? "" : res.get("message")));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 409) return CaptureResult.failed("auth_expired", "authorisation no longer capturable");
            throw new PaymentGatewayException(NAME + " capture failed", e);
        }
    }

    @Override
    public RefundResult refund(String pspRef, Money amount, String idempotencyKey, String reason) {
        try {
            Map<?, ?> res = http.post().uri("/v1/refunds")
                .header("Idempotency-Key", idempotencyKey)
                .body(Map.of("charge", pspRef, "amount", minor(amount), "reason", reason == null ? "" : reason)).retrieve().body(Map.class);
            if (res != null && "succeeded".equals(res.get("status"))) return RefundResult.succeeded(String.valueOf(res.get("id")));
            return RefundResult.failed(String.valueOf(res == null ? "unknown" : res.getOrDefault("code", "refund_rejected")), String.valueOf(res == null ? "" : res.get("message")));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 402) return RefundResult.failed("refund_rejected", e.getResponseBodyAsString());
            throw new PaymentGatewayException(NAME + " refund failed", e);
        }
    }

    @Override
    public void voidAuth(String pspRef) {
        try { http.post().uri("/v1/authorizations/{id}/void", pspRef).retrieve().toBodilessEntity(); }
        catch (RestClientResponseException e) { if (e.getStatusCode().value() != 404) throw new PaymentGatewayException(NAME + " void failed", e); }
    }

    /** Minor units (cents) as most PSPs expect; adjust for zero-decimal currencies. */
    private static long minor(Money m) { return m.amount().movePointRight(2).longValueExact(); }
}
