package ai.intentoms.platform.security;

import ai.intentoms.platform.error.ForbiddenException;
import org.springframework.stereotype.Service;

import java.util.Optional;

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

    /** Actor reference for ledger events: "user:alice", "agent:shopbot-7", "system:sweeper", "decision:<id>". */
    /** Platform role (not a tenant permission): may act across tenants. A tenant admin's "*" permission does not count. */
    public static final String PLATFORM_ROLE = "platform_engineer";

    public boolean isPlatformOperator() {
        return currentPrincipal().map(p -> p.type() == Principal.Type.SYSTEM || (p.roles() != null && p.roles().contains(PLATFORM_ROLE))).orElse(false);
    }

    public String actorRef() { return currentPrincipal().map(Principal::actorRef).orElse("system:anonymous"); }
}
