package ai.intentoms.platform.tenant;

import ai.intentoms.platform.security.Principal;
import ai.intentoms.platform.security.SecurityContextHolderUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Binds the tenant for the request. Order: authenticated principal's tenant wins; otherwise the X-Tenant-Id header
 * (only allowed for unauthenticated public endpoints such as promise reads, which are tenant-keyed by the caller).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class TenantFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Tenant-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String tenant = SecurityContextHolderUtil.currentPrincipal().map(Principal::tenantId).orElse(null);
        if (tenant == null) tenant = req.getHeader(HEADER);
        if (tenant != null && !tenant.isBlank()) TenantContext.set(tenant);
        try {
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();
        }
    }
}
