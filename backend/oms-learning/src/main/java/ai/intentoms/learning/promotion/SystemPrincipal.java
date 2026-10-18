package ai.intentoms.learning.promotion;

import ai.intentoms.platform.security.Principal;
import ai.intentoms.platform.security.PrincipalAuthentication;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.function.Supplier;

/**
 * Runs studio and config steps as the tenant's system principal and restores whoever was authenticated before. The
 * decision studio refuses model and agent principals (GR-14) but accepts the system one, which is how a promotion the
 * EX-07 gate already approved is applied. Exceptions propagate; callers decide whether to swallow them.
 */
public final class SystemPrincipal {
    private SystemPrincipal() {}

    public static <T> T call(Supplier<T> body) {
        Authentication prev = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.getContext().setAuthentication(new PrincipalAuthentication(Principal.system(TenantContext.require())));
        try { return body.get(); }
        finally {
            if (prev == null) SecurityContextHolder.clearContext();
            else SecurityContextHolder.getContext().setAuthentication(prev);
        }
    }

    public static void run(Runnable body) { call(() -> { body.run(); return null; }); }
}
