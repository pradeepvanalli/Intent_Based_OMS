package ai.intentoms.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Resolves a Principal from (in order) an API key ("Authorization: Bearer oms_..." or "X-Api-Key"), an OIDC bearer
 * token when {@code oms.security.oidc.*} is configured (NFR-07), or a locally issued HS256 JWT. OIDC validation
 * failures fall back to the local verifier so local users keep working alongside SSO. Agent request signatures (HMAC)
 * are verified by the agent module's own filter which also produces a Principal.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthenticationFilter extends OncePerRequestFilter {
    private final ApiKeyService apiKeys;
    private final JwtService jwt;
    private final OidcTokenAuthenticator oidc;

    public AuthenticationFilter(ApiKeyService apiKeys, JwtService jwt, OidcTokenAuthenticator oidc) {
        this.apiKeys = apiKeys;
        this.jwt = jwt;
        this.oidc = oidc;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            Optional<Principal> p = resolve(req);
            p.ifPresent(principal -> SecurityContextHolder.getContext().setAuthentication(new PrincipalAuthentication(principal)));
        }
        try {
            chain.doFilter(req, res);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private Optional<Principal> resolve(HttpServletRequest req) {
        String apiKey = req.getHeader("X-Api-Key");
        if (apiKey != null) return apiKeys.authenticate(apiKey.trim());
        String auth = req.getHeader("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = auth.substring(7).trim();
            if (token.startsWith("oms_")) return apiKeys.authenticate(token);
            return bearer(token);
        }
        return Optional.empty();
    }

    private Optional<Principal> bearer(String token) {
        if (!oidc.enabled()) return jwt.parse(token);
        // Route on the declared algorithm so local HS256 tokens skip the JWKS path, then always try the other verifier.
        if (OidcTokenAuthenticator.looksAsymmetric(token)) {
            Optional<Principal> p = oidc.authenticate(token);
            return p.isPresent() ? p : jwt.parse(token);
        }
        Optional<Principal> p = jwt.parse(token);
        return p.isPresent() ? p : oidc.authenticate(token);
    }
}
