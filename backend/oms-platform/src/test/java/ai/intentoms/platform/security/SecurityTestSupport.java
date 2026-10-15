package ai.intentoms.platform.security;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Builders for {@link SecurityProperties} in unit tests (records have no defaults outside the Boot binder). */
public final class SecurityTestSupport {
    private SecurityTestSupport() {}

    public static SecurityProperties.Oidc oidc(String issuer, String jwks, String audience, Map<String, String> roleMapping) {
        return new SecurityProperties.Oidc(issuer, jwks, audience, List.of("preferred_username", "sub"), "tenant", "",
            "roles", List.of("realm_access.roles", "groups"), roleMapping, Duration.ofSeconds(60));
    }

    public static SecurityProperties props(Boolean hsts, boolean enforceProd) {
        return new SecurityProperties(SecurityProperties.DEFAULT_JWT_SECRET, "", Duration.ofHours(8), List.of(), List.of(), enforceProd, hsts,
            SecurityHeadersFilter.DEFAULT_CSP, SecurityHeadersFilter.DEFAULT_SWAGGER_CSP, SecurityHeadersFilter.DEFAULT_PERMISSIONS_POLICY,
            false, 10, Duration.ofMinutes(1), 12, "", new SecurityProperties.Cors(List.of("*"), false),
            oidc("", "", "", Map.of()));
    }
}
