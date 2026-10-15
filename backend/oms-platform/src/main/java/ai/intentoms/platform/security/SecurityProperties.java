package ai.intentoms.platform.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * oms.security.* settings (NFR-07 authentication, NFR-08 data protection). See {@code backend/oms-platform/HARDENING.md}
 * for the full property table and the production checklist.
 *
 * @param jwtSecret          HS256 secret for locally issued session tokens (>= 32 chars in production; injected from a secret store)
 * @param jwtSecretFile      optional path of a file whose trimmed content replaces {@code jwtSecret} (Kubernetes/Docker secret mounts)
 * @param jwtTtl             session token lifetime
 * @param devUsers           bootstrap users for local/dev: id:password:tenant:role1|role2 (must be empty in production)
 * @param publicPaths        paths served without authentication (health, docs, promise reads, login, {@code /api/v1/public/**} demo tenant list, {@code /public/track/**} order tracking, SPA assets)
 * @param enforceProduction  apply the production readiness checks even when the {@code prod} profile is not active
 * @param hsts               emit Strict-Transport-Security; {@code null} means "on in production, off otherwise"
 * @param contentSecurityPolicy  CSP for the bundled SPA; {@code {inline-script-hashes}} expands to the sha256 hashes of inline scripts in static/index.html
 * @param swaggerContentSecurityPolicy  relaxed CSP applied to /swagger-ui and /v3/api-docs
 * @param permissionsPolicy  Permissions-Policy header value
 * @param trustForwardedFor  take the client IP for login rate limiting from the first {@code X-Forwarded-For} hop (only behind a trusted proxy)
 * @param loginRateLimit     login attempts allowed per IP and per user within {@code loginRateWindow}
 * @param loginRateWindow    refill window of the login rate limiter
 * @param passwordMinLength  minimum length of passwords set through the API (dev users are exempt)
 * @param masterKey          optional AES-256 master key (base64 of 32 bytes, or any passphrase which is SHA-256 hashed) that envelope-encrypts tenant_key.key_material
 * @param cors               CORS settings
 * @param oidc               optional OIDC resource-server settings
 */
@ConfigurationProperties(prefix = "oms.security")
public record SecurityProperties(
    @DefaultValue(DEFAULT_JWT_SECRET) String jwtSecret,
    @DefaultValue("") String jwtSecretFile,
    @DefaultValue("8h") Duration jwtTtl,
    @DefaultValue({}) List<String> devUsers,
    @DefaultValue({"/actuator/health/**", "/actuator/prometheus", "/v3/api-docs/**", "/swagger-ui/**", "/api/v1/auth/login", "/api/v1/auth/logout",
        "/api/v1/promise/**", "/api/v1/public/**", "/public/**", "/.well-known/**", "/", "/index.html", "/assets/**", "/favicon.ico", "/favicon.svg", "/app/**"}) List<String> publicPaths,
    @DefaultValue("false") boolean enforceProduction,
    Boolean hsts,
    @DefaultValue(SecurityHeadersFilter.DEFAULT_CSP) String contentSecurityPolicy,
    @DefaultValue(SecurityHeadersFilter.DEFAULT_SWAGGER_CSP) String swaggerContentSecurityPolicy,
    @DefaultValue(SecurityHeadersFilter.DEFAULT_PERMISSIONS_POLICY) String permissionsPolicy,
    @DefaultValue("false") boolean trustForwardedFor,
    @DefaultValue("10") int loginRateLimit,
    @DefaultValue("1m") Duration loginRateWindow,
    @DefaultValue("12") int passwordMinLength,
    @DefaultValue("") String masterKey,
    @DefaultValue Cors cors,
    @DefaultValue Oidc oidc
) {
    public static final String DEFAULT_JWT_SECRET = "change-me-in-production-please-32chars";

    /** The HS256 secret actually used: the content of {@link #jwtSecretFile} when set, otherwise {@link #jwtSecret}. */
    public String effectiveJwtSecret() {
        return SecretSources.resolve(jwtSecret, jwtSecretFile);
    }

    /**
     * @param allowedOrigins browser origins allowed to call the API; {@code *} (the default) is refused in production
     * @param allowCredentials whether cookies/authorization headers may be sent cross-origin (requires explicit origins)
     */
    public record Cors(
        @DefaultValue("*") List<String> allowedOrigins,
        @DefaultValue("false") boolean allowCredentials
    ) {
        public boolean allowsAny() { return allowedOrigins == null || allowedOrigins.isEmpty() || allowedOrigins.contains("*"); }
    }

    /**
     * OIDC resource-server settings (NFR-07). Enabled when {@code issuerUri} or {@code jwkSetUri} is set.
     *
     * @param issuerUri      expected {@code iss}; when {@code jwkSetUri} is blank the JWKS location is discovered from
     *                       {@code <issuer>/.well-known/openid-configuration}
     * @param jwkSetUri      explicit JWKS endpoint (skips discovery)
     * @param audience       required {@code aud} value (any match); blank disables the audience check
     * @param subjectClaims  claims tried in order for the principal id (default preferred_username, then sub)
     * @param tenantClaim    claim (path) carrying the OMS tenant id
     * @param defaultTenant  tenant used when the token carries no tenant claim (single-tenant deployments); blank rejects such tokens
     * @param rolesClaim     primary roles claim path (default {@code roles})
     * @param rolesClaimPaths additional role/group claim paths, dot or slash separated, e.g. Keycloak {@code realm_access.roles},
     *                       {@code resource_access.oms.roles}, Entra {@code groups}
     * @param roleMapping    IdP role/group name (or Entra group object id) → OMS role; unmapped values are kept only when they are built-in OMS roles
     * @param clockSkew      accepted clock skew when validating exp/nbf
     */
    public record Oidc(
        @DefaultValue("") String issuerUri,
        @DefaultValue("") String jwkSetUri,
        @DefaultValue("") String audience,
        @DefaultValue({"preferred_username", "sub"}) List<String> subjectClaims,
        @DefaultValue("tenant") String tenantClaim,
        @DefaultValue("") String defaultTenant,
        @DefaultValue("roles") String rolesClaim,
        @DefaultValue({"realm_access.roles", "groups"}) List<String> rolesClaimPaths,
        @DefaultValue Map<String, String> roleMapping,
        @DefaultValue("60s") Duration clockSkew
    ) {
        public boolean enabled() { return !isBlank(issuerUri) || !isBlank(jwkSetUri); }

        private static boolean isBlank(String s) { return s == null || s.isBlank(); }
    }
}
