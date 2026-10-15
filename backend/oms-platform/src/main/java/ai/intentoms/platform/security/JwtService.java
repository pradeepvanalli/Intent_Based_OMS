package ai.intentoms.platform.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Bearer JWTs for locally authenticated UI sessions: HS256 with a per-deployment secret ({@code oms.security.jwt-secret} or
 * {@code jwt-secret-file}). Tokens from an external IdP are verified by {@link OidcTokenAuthenticator} instead.
 * Claims: sub, tenant, roles[], perms[], type, tier.
 */
@Service
public class JwtService {
    private final SecretKey key;
    private final Duration ttl;

    public JwtService(SecurityProperties props) {
        String secret = props.effectiveJwtSecret();
        if (secret.length() < 32) secret = (secret + "0".repeat(32)).substring(0, 32);
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttl = props.jwtTtl();
    }

    public String issue(Principal p) {
        Instant now = Instant.now();
        return Jwts.builder()
            .subject(p.id())
            .claim("tenant", p.tenantId())
            .claim("type", p.type().name())
            .claim("roles", List.copyOf(p.roles()))
            .claim("perms", List.copyOf(p.permissions()))
            .claim("tier", p.trustTier())
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(ttl)))
            .signWith(key)
            .compact();
    }

    @SuppressWarnings("unchecked")
    public Optional<Principal> parse(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            Set<String> roles = new HashSet<>((List<String>) c.getOrDefault("roles", List.of()));
            Set<String> perms = new HashSet<>((List<String>) c.getOrDefault("perms", List.of()));
            Principal.Type type = Principal.Type.valueOf((String) c.getOrDefault("type", "USER"));
            int tier = ((Number) c.getOrDefault("tier", 1)).intValue();
            return Optional.of(new Principal(type, c.getSubject(), c.get("tenant", String.class), roles, perms, tier));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
