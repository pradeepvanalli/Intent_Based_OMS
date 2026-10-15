package ai.intentoms.platform.tenant;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Carries the current tenant across a unit of work. Set by {@link TenantFilter} for HTTP requests and by
 * background workers (workflow, sweepers) when they pick up a tenant-scoped job.
 * Every repository must scope reads and writes by {@link #require()}.
 */
public final class TenantContext {
    private static final ThreadLocal<String> CURRENT = new InheritableThreadLocal<>();

    private TenantContext() {}

    public static Optional<String> current() { return Optional.ofNullable(CURRENT.get()); }

    public static String require() {
        String t = CURRENT.get();
        if (t == null) throw new IllegalStateException("No tenant bound to the current thread");
        return t;
    }

    public static void set(String tenantId) { CURRENT.set(Objects.requireNonNull(tenantId, "tenantId")); }

    public static void clear() { CURRENT.remove(); }

    public static void runAs(String tenantId, Runnable r) {
        String prev = CURRENT.get();
        set(tenantId);
        try { r.run(); } finally { if (prev == null) CURRENT.remove(); else CURRENT.set(prev); }
    }

    public static <T> T callAs(String tenantId, Callable<T> c) throws Exception {
        String prev = CURRENT.get();
        set(tenantId);
        try { return c.call(); } finally { if (prev == null) CURRENT.remove(); else CURRENT.set(prev); }
    }

    public static <T> T supplyAs(String tenantId, java.util.function.Supplier<T> s) {
        String prev = CURRENT.get();
        set(tenantId);
        try { return s.get(); } finally { if (prev == null) CURRENT.remove(); else CURRENT.set(prev); }
    }
}
