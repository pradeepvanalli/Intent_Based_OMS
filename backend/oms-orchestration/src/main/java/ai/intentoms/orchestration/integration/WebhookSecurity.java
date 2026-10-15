package ai.intentoms.orchestration.integration;

import ai.intentoms.orchestration.config.OrchestrationConfigKeys;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.error.UnauthorizedException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.security.Principal;
import ai.intentoms.platform.security.PrincipalAuthentication;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Tenant resolution and signature verification for the public {@code /webhooks/**} endpoints (FR-WF-05, FR-INV-04). The
 * tenant comes from {@code X-Tenant-Id} (required); when {@code tenant_config webhooks.inbound_secret} is set the request
 * must carry {@code X-Webhook-Signature} = HMAC-SHA256(secret, raw body) as hex or base64 (constant-time compare). The body
 * then runs under that tenant with an integration principal.
 */
@Component
public class WebhookSecurity {
    public static final String SIGNATURE_HEADER = "X-Webhook-Signature";
    private final TenantConfigStore config;
    private final AuthorizationService authz;
    private final Metrics metrics;

    public WebhookSecurity(TenantConfigStore config, AuthorizationService authz, Metrics metrics) {
        this.config = config;
        this.authz = authz;
        this.metrics = metrics;
    }

    public <T> T run(String tenantHeader, String source, String rawBody, String signature, Supplier<T> body) {
        String tenant = tenantHeader == null || tenantHeader.isBlank() ? TenantContext.current().orElse(null) : tenantHeader.trim();
        if (tenant == null) throw new ValidationException("X-Tenant-Id header is required");
        return TenantContext.supplyAs(tenant, () -> {
            verify(source, rawBody, signature);
            boolean installed = authz.currentPrincipal().isEmpty();
            Authentication prev = SecurityContextHolder.getContext().getAuthentication();
            if (installed) SecurityContextHolder.getContext().setAuthentication(new PrincipalAuthentication(
                new Principal(Principal.Type.SYSTEM, source + "-webhook", tenant, Set.of("integration"), Set.of("*"), 3)));
            try { return body.get(); } finally { if (installed) { if (prev == null) SecurityContextHolder.clearContext(); else SecurityContextHolder.getContext().setAuthentication(prev); } }
        });
    }

    void verify(String source, String rawBody, String signature) {
        String secret = config.getString(OrchestrationConfigKeys.WEBHOOK_INBOUND_SECRET, null);
        if (secret == null || secret.isBlank()) return;
        if (signature == null || signature.isBlank()) throw new UnauthorizedException("Missing " + SIGNATURE_HEADER);
        byte[] mac = hmac(secret, rawBody == null ? "" : rawBody);
        String sig = signature.trim();
        if (sig.toLowerCase().startsWith("sha256=")) sig = sig.substring(7);
        boolean ok = MessageDigest.isEqual(HexFormat.of().formatHex(mac).getBytes(StandardCharsets.UTF_8), sig.toLowerCase().getBytes(StandardCharsets.UTF_8))
            || MessageDigest.isEqual(Base64.getEncoder().encodeToString(mac).getBytes(StandardCharsets.UTF_8), sig.getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            metrics.count("oms.orchestration.webhook.rejected", "source", source);
            throw new UnauthorizedException("Invalid webhook signature");
        }
    }

    public static byte[] hmac(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static String hmacHex(String secret, String body) { return HexFormat.of().formatHex(hmac(secret, body)); }
}
