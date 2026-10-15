package ai.intentoms.integrations.graphql;

import ai.intentoms.platform.tenant.TenantContext;
import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * Registers the OMS tenant as a Micrometer {@link ThreadLocalAccessor} so Spring for GraphQL's context snapshots carry
 * it into data fetchers and batch loaders exactly like the Spring Security context (the fetchers run on the request
 * thread today; this keeps them correct if an async fetcher is ever introduced).
 */
@Component
public class TenantContextPropagation {
    public static final String KEY = "oms.tenant";

    @PostConstruct
    void register() {
        ContextRegistry registry = ContextRegistry.getInstance();
        boolean present = registry.getThreadLocalAccessors().stream().anyMatch(a -> KEY.equals(a.key()));
        if (!present) registry.registerThreadLocalAccessor(KEY, () -> TenantContext.current().orElse(null), TenantContext::set, TenantContext::clear);
    }
}
