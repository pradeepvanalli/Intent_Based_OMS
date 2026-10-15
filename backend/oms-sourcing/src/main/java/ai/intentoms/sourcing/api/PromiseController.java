package ai.intentoms.sourcing.api;

import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.platform.tenant.TenantFilter;
import ai.intentoms.sourcing.PromiseService;
import ai.intentoms.sourcing.model.Promise;
import ai.intentoms.sourcing.model.PromiseRequest;
import ai.intentoms.sourcing.promise.DefaultPromiseService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Public promise endpoint (FR-SRC-05): {@code /api/v1/promise/**} is in the security public paths, so the tenant is
 * resolved from the {@code X-Tenant-Id} header by {@link TenantFilter} (an authenticated caller's tenant wins).
 */
@RestController
@RequestMapping("/api/v1/promise")
public class PromiseController {
    private final PromiseService promise;
    private final DefaultPromiseService impl;

    public PromiseController(PromiseService promise, DefaultPromiseService impl) {
        this.promise = promise;
        this.impl = impl;
    }

    @PostMapping
    public Promise promise(@RequestBody PromiseRequest body) {
        if (TenantContext.current().isEmpty()) throw new ValidationException("Header " + TenantFilter.HEADER + " is required for promise requests");
        return promise.promise(body);
    }

    /** Cache statistics; the path is public, so the permission check is what keeps it to authenticated readers. */
    @GetMapping("/cache")
    @RequiresPermission({Permissions.ORDER_READ, Permissions.CONFIG_ADMIN})
    public Map<String, Object> cache() { return impl.cacheStats(); }
}
