package ai.intentoms.platform.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Validates bearer tokens issued by an external OpenID Connect provider (NFR-07 SSO) and maps their claims to an OMS
 * {@link Principal}. Disabled unless {@code oms.security.oidc.issuer-uri} or {@code jwk-set-uri} is set.
 * <p>
 * The {@link JwtDecoder} is built lazily on first use (so an unreachable IdP never blocks startup) and cached; Nimbus
 * caches the JWKS and refreshes it on unknown {@code kid}. A build failure is retried after a short back-off.
 * Mapping: id = first present of {@code subject-claims}; tenant = {@code tenant-claim} (or {@code default-tenant});
 * roles = union of {@code roles-claim} and every {@code roles-claim-paths} entry, run through {@code role-mapping};
 * permissions = {@link Roles#permissionsFor(Set)}; type USER, trust tier 3.
 */
@Component
public class OidcTokenAuthenticator {
    private static final Logger log = LoggerFactory.getLogger(OidcTokenAuthenticator.class);
    private static final long BUILD_RETRY_MS = 15_000;

    private final SecurityProperties.Oidc cfg;
    /** role-mapping keys lower-cased: Boot's relaxed binding lower-cases unbracketed map keys, so match IdP names case-insensitively. */
    private final Map<String, String> lowerMapping;
    private final AtomicReference<JwtDecoder> decoder = new AtomicReference<>();
    private volatile long nextBuildAttempt;

    @org.springframework.beans.factory.annotation.Autowired
    public OidcTokenAuthenticator(SecurityProperties props) { this(props.oidc()); }

    public OidcTokenAuthenticator(SecurityProperties.Oidc cfg) {
        this.cfg = cfg;
        Map<String, String> lower = new java.util.HashMap<>();
        cfg.roleMapping().forEach((k, v) -> lower.put(k.toLowerCase(Locale.ROOT), v));
        this.lowerMapping = Map.copyOf(lower);
    }

    public boolean enabled() { return cfg.enabled(); }

    /** True when the compact JWT header declares an asymmetric algorithm (RS, ES, PS or EdDSA families), i.e. it cannot be a local HS256 token. */
    public static boolean looksAsymmetric(String token) {
        try {
            int dot = token.indexOf('.');
            if (dot <= 0) return false;
            String header = new String(Base64.getUrlDecoder().decode(token.substring(0, dot)), StandardCharsets.UTF_8);
            int i = header.indexOf("\"alg\"");
            if (i < 0) return false;
            int q1 = header.indexOf('"', header.indexOf(':', i) + 1);
            int q2 = header.indexOf('"', q1 + 1);
            String alg = header.substring(q1 + 1, q2).toUpperCase(Locale.ROOT);
            return alg.startsWith("RS") || alg.startsWith("ES") || alg.startsWith("PS") || alg.startsWith("EDDSA");
        } catch (Exception e) {
            return false;
        }
    }

    public Optional<Principal> authenticate(String token) {
        if (!enabled()) return Optional.empty();
        JwtDecoder d = decoder();
        if (d == null) return Optional.empty();
        Jwt jwt;
        try {
            jwt = d.decode(token);
        } catch (Exception e) {
            log.debug("OIDC token rejected: {}", e.getMessage());
            return Optional.empty();
        }
        return map(jwt.getClaims());
    }

    /** Pure claim → principal mapping (package-visible for tests). Empty when no subject or tenant can be determined. */
    Optional<Principal> map(Map<String, Object> claims) {
        String id = null;
        for (String c : cfg.subjectClaims()) {
            Object v = path(claims, c);
            if (v instanceof String s && !s.isBlank()) { id = s; break; }
        }
        if (id == null) return Optional.empty();
        Object tenantV = path(claims, cfg.tenantClaim());
        String tenant = tenantV instanceof String s && !s.isBlank() ? s : (cfg.defaultTenant().isBlank() ? null : cfg.defaultTenant());
        if (tenant == null) {
            log.debug("OIDC token for {} carries no '{}' claim and no default tenant is configured", id, cfg.tenantClaim());
            return Optional.empty();
        }
        Set<String> idpRoles = new LinkedHashSet<>();
        List<String> paths = new ArrayList<>();
        if (!cfg.rolesClaim().isBlank()) paths.add(cfg.rolesClaim());
        paths.addAll(cfg.rolesClaimPaths());
        for (String p : paths) collectStrings(path(claims, p), idpRoles);
        Set<String> roles = new LinkedHashSet<>();
        for (String r : idpRoles) {
            String mapped = mapping(r);
            if (mapped == null && r.startsWith("/")) mapped = mapping(r.substring(1)); // Keycloak group paths
            if (mapped != null) { for (String m : mapped.split("[|,]")) if (!m.isBlank()) roles.add(m.trim()); }
            else if (Roles.DEFAULT.containsKey(r)) roles.add(r);
        }
        Set<String> frozenRoles = Set.copyOf(roles);
        return Optional.of(new Principal(Principal.Type.USER, id, tenant, frozenRoles, Roles.permissionsFor(frozenRoles), 3));
    }

    private String mapping(String idpRole) {
        String m = cfg.roleMapping().get(idpRole);
        return m != null ? m : lowerMapping.get(idpRole.toLowerCase(Locale.ROOT));
    }

    private static void collectStrings(Object v, Set<String> out) {
        if (v instanceof String s) { for (String part : s.split("[\\s,]+")) if (!part.isBlank()) out.add(part); }
        else if (v instanceof Collection<?> c) { for (Object o : c) if (o != null) out.add(o.toString()); }
    }

    /** Navigate a dot- or slash-separated path ("realm_access.roles", "/resource_access/oms/roles") through nested maps. */
    static Object path(Map<String, Object> root, String path) {
        if (path == null || path.isBlank()) return null;
        if (root.containsKey(path)) return root.get(path);
        Object cur = root;
        for (String seg : path.replace('/', '.').split("\\.")) {
            if (seg.isEmpty()) continue;
            if (!(cur instanceof Map<?, ?> m)) return null;
            cur = m.get(seg);
            if (cur == null) return null;
        }
        return cur;
    }

    private JwtDecoder decoder() {
        JwtDecoder d = decoder.get();
        if (d != null) return d;
        long now = System.currentTimeMillis();
        if (now < nextBuildAttempt) return null;
        synchronized (this) {
            d = decoder.get();
            if (d != null) return d;
            try {
                d = build();
                decoder.set(d);
                log.info("OIDC resource server enabled (issuer={}, jwks={}, audience={})", blankToDash(cfg.issuerUri()), blankToDash(cfg.jwkSetUri()), blankToDash(cfg.audience()));
                return d;
            } catch (Exception e) {
                nextBuildAttempt = System.currentTimeMillis() + BUILD_RETRY_MS;
                log.warn("Cannot initialise OIDC JwtDecoder (will retry): {}", e.toString());
                return null;
            }
        }
    }

    private JwtDecoder build() {
        NimbusJwtDecoder nimbus = cfg.jwkSetUri().isBlank()
            ? NimbusJwtDecoder.withIssuerLocation(cfg.issuerUri()).build()
            : NimbusJwtDecoder.withJwkSetUri(cfg.jwkSetUri()).build();
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(new JwtTimestampValidator(cfg.clockSkew()));
        if (!cfg.issuerUri().isBlank()) validators.add(new JwtIssuerValidator(cfg.issuerUri()));
        if (!cfg.audience().isBlank()) validators.add(new AudienceValidator(cfg.audience()));
        nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return nimbus;
    }

    private static String blankToDash(String s) { return s == null || s.isBlank() ? "-" : s; }

    /** Requires the configured audience to appear in {@code aud}. */
    record AudienceValidator(String audience) implements OAuth2TokenValidator<Jwt> {
        @Override public OAuth2TokenValidatorResult validate(Jwt jwt) {
            List<String> aud = jwt.getAudience();
            if (aud != null && aud.contains(audience)) return OAuth2TokenValidatorResult.success();
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "The required audience " + audience + " is missing", null));
        }
    }

    /** Convenience for tests and diagnostics. */
    static boolean expired(Map<String, Object> claims) {
        Object exp = claims.get(JwtClaimNames.EXP);
        return exp instanceof Instant i && i.isBefore(Instant.now());
    }
}
