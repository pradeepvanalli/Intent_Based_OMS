package ai.intentoms.platform.security;

import ai.intentoms.platform.error.ForbiddenException;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Programmatic access to the current principal and permission checks. */
@Service
public class AuthorizationService {
    public Optional<Principal> currentPrincipal() { return SecurityContextHolderUtil.currentPrincipal(); }

    public Principal requirePrincipal() {
        return currentPrincipal().orElseThrow(() -> new ForbiddenException("Authentication required"));
    }

    public void require(String permission) {
        Principal p = requirePrincipal();
        if (!p.has(permission)) throw new ForbiddenException("Missing permission " + permission);
    }

    public boolean has(String permission) {
        return currentPrincipal().map(p -> p.has(permission)).orElse(false);
    }

    /** Platform role (not a tenant permission): may act across tenants. A tenant admin's "*" permission does not count. */
    public static final String PLATFORM_ROLE = "platform_engineer";
    /** The role of the platform's own system principal ({@link Principal#system}); no credential a tenant mints carries it. */
    public static final String SYSTEM_ROLE = "system";
    /**
     * D-1: roles only a platform operator may hand out. The platform role crosses tenants; the internal principals' roles
     * ("system", "decision") would make a minted credential look like the platform's own jobs.
     */
    public static final Set<String> RESERVED_ROLES = Set.of(PLATFORM_ROLE, SYSTEM_ROLE, "decision");

    public boolean isPlatformOperator() { return currentPrincipal().map(AuthorizationService::isPlatformOperator).orElse(false); }

    /**
     * The platform's own system principal or a holder of the platform role. A SYSTEM-typed API key or webhook principal
     * is a tenant integration, not a platform operator: a tenant admin may mint SYSTEM keys for integrations, so the
     * principal type alone must never open a cross-tenant endpoint (D-1).
     */
    public static boolean isPlatformOperator(Principal p) {
        if (p == null || p.roles() == null) return false;
        return p.roles().contains(PLATFORM_ROLE) || (p.type() == Principal.Type.SYSTEM && p.roles().contains(SYSTEM_ROLE));
    }

    /**
     * D-1: nobody grants a role or a permission they do not hold. A platform operator may grant anything; anyone else
     * only roles whose every permission they hold themselves (never a {@link #RESERVED_ROLES reserved} role) and only
     * permissions they hold. Throws {@link ForbiddenException} naming what was refused. Called wherever roles or a
     * permission set are stored for a new user, API key or agent credential.
     */
    public void requireMayGrant(Collection<String> roles, Collection<String> permissions) {
        Principal caller = requirePrincipal();
        if (isPlatformOperator(caller)) return;
        Set<String> reserved = new TreeSet<>(), rolesBeyond = new TreeSet<>(), missing = new TreeSet<>();
        for (String role : roles == null ? Set.<String>of() : roles) {
            if (role == null) continue;
            if (RESERVED_ROLES.contains(role)) { reserved.add(role); continue; }
            for (String perm : Roles.DEFAULT.getOrDefault(role, Set.of())) {
                if (!caller.has(perm)) { rolesBeyond.add(role); missing.add(perm); }
            }
        }
        for (String perm : permissions == null ? Set.<String>of() : permissions) {
            if (perm != null && !caller.has(perm)) missing.add(perm);
        }
        if (!reserved.isEmpty()) {
            throw new ForbiddenException("Only a platform operator may grant " + plural("role", reserved) + " " + String.join(", ", reserved)
                + "; a tenant administrator cannot create platform credentials");
        }
        if (!missing.isEmpty()) {
            throw new ForbiddenException("You cannot grant what you do not hold: "
                + (rolesBeyond.isEmpty() ? "" : plural("role", rolesBeyond) + " " + String.join(", ", rolesBeyond) + " needs ")
                + plural("permission", missing) + " " + String.join(", ", missing));
        }
    }

    private static String plural(String word, Set<String> items) { return items.size() == 1 ? word : word + "s"; }

    /** Actor reference for ledger events: "user:alice", "agent:shopbot-7", "system:sweeper", "decision:<id>". */
    public String actorRef() { return currentPrincipal().map(Principal::actorRef).orElse("system:anonymous"); }
}
