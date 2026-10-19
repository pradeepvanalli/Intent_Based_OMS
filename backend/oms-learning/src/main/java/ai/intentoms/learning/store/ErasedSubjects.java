package ai.intentoms.learning.store;

import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.pii.PiiShreddedEvent;
import ai.intentoms.platform.tenant.TenantContext;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * GV-06 / audit defect N2: whether a customer reference names an erased customer. An erasure request
 * ({@code pii_erasure}) stores the SHA-256 of (tenant, customer reference) and the pseudonym that replaced the
 * reference on orders; later events can carry either (a system that still knows the raw reference, or the
 * pseudonymised order). Learning checks every customer it is about to write facts, preference evidence, order
 * attribution or segment membership for, so an erased customer is never learned again under either name.
 * Answers are cached for five minutes and dropped on every erasure.
 */
@Component
public class ErasedSubjects {
    private final JdbcClient jdbc;
    private final Cache<String, Boolean> cache = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(5)).maximumSize(50_000).build();

    public ErasedSubjects(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** True when {@code customerRef} is an erased customer's reference or pseudonym in the current tenant. */
    public boolean erased(String customerRef) {
        if (customerRef == null || customerRef.isBlank()) return false;
        String tenant = TenantContext.current().orElse(null);
        if (tenant == null) return false;
        return cache.get(tenant + "|" + customerRef, k -> {
            String hash = Json.sha256(Map.of("tenant", tenant, "subject", customerRef));
            return jdbc.sql("select exists(select 1 from pii_erasure where tenant_id = :t and (pseudonym = :p or subject_hash = :h))")
                .param("t", tenant).param("p", customerRef).param("h", hash).query(Boolean.class).single();
        });
    }

    @EventListener
    public void onErasure(PiiShreddedEvent e) { cache.invalidateAll(); }
}
