package ai.intentoms.platform.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

public final class SecurityContextHolderUtil {
    private SecurityContextHolderUtil() {}

    public static Optional<Principal> currentPrincipal() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a instanceof PrincipalAuthentication pa) return Optional.of(pa.principal());
        return Optional.empty();
    }
}
