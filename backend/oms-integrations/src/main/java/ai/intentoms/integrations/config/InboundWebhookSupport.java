package ai.intentoms.integrations.config;

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

import java.util.Set;
import java.util.function.Supplier;

/**
 * Tenant resolution, per-source HMAC verification and principal installation for the module's public
 * {@code /webhooks/**} endpoints (marketplace, EasyPost). The tenant comes from {@code X-Tenant-Id} or {@code ?tenant=}
 * (aggregators such as EasyPost cannot set custom headers); the secret key is looked up in {@code tenant_config} under
 * the caller's key and, when present, makes the signature header mandatory (constant-time compare, see
 * {@link HmacSignatures}). The body runs under that tenant with a SYSTEM principal carrying only the permissions the
 * connector needs.
 */
@Component
public class InboundWebhookSupport {
    private final TenantConfigStore config;
    private final AuthorizationService authz;
    private final Metrics metrics;

    public InboundWebhookSupport(TenantConfigStore config, AuthorizationService authz, Metrics metrics) {
        this.config = config;
        this.authz = authz;
        this.metrics = metrics;
    }

    public <T> T run(String tenantHeader, String tenantParam, String source, String secretKey, String rawBody, String signature, Set<String> permissions, Supplier<T> body) {
        String tenant = first(tenantHeader, tenantParam);
        if (tenant == null) tenant = TenantContext.current().orElseThrow(() -> new ValidationException("Tenant required: send X-Tenant-Id or ?tenant="));
        String finalTenant = tenant;
        return TenantContext.supplyAs(tenant, () -> {
            verify(source, secretKey, rawBody, signature);
            boolean install = authz.currentPrincipal().isEmpty();
            Authentication prev = SecurityContextHolder.getContext().getAuthentication();
            if (install) SecurityContextHolder.getContext().setAuthentication(new PrincipalAuthentication(
                new Principal(Principal.Type.SYSTEM, source + "-webhook", finalTenant, Set.of("integration"), permissions, 3)));
            try { return body.get(); } finally {
                if (install) { if (prev == null) SecurityContextHolder.clearContext(); else SecurityContextHolder.getContext().setAuthentication(prev); }
            }
        });
    }

    void verify(String source, String secretKey, String rawBody, String signature) {
        String secret = secretKey == null ? null : config.getString(secretKey, null);
        if (secret == null || secret.isBlank()) return;
        if (signature == null || signature.isBlank()) {
            metrics.count("oms.integrations.webhook.rejected", "source", source, "reason", "missing_signature");
            throw new UnauthorizedException("Missing webhook signature for " + source);
        }
        if (!HmacSignatures.matches(secret, rawBody, signature)) {
            metrics.count("oms.integrations.webhook.rejected", "source", source, "reason", "bad_signature");
            throw new UnauthorizedException("Invalid webhook signature for " + source);
        }
    }

    private static String first(String a, String b) {
        if (a != null && !a.isBlank()) return a.trim();
        if (b != null && !b.isBlank()) return b.trim();
        return null;
    }
}
