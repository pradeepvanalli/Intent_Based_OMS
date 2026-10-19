package ai.intentoms.platform.api;

import ai.intentoms.platform.audit.AuditLog;
import ai.intentoms.platform.error.ForbiddenException;
import ai.intentoms.platform.error.UnauthorizedException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.ratelimit.RateLimiter;
import ai.intentoms.platform.security.*;
import ai.intentoms.platform.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Local authentication (NFR-07): password login issuing HS256 session tokens, logout, self-service password change,
 * user administration and API-key issuance. Login is rate limited per client IP and per user through the platform
 * {@link RateLimiter} (429 when exceeded), takes a constant-time path for unknown users and records
 * {@code auth.login} / {@code auth.login_failed} in the audit log. SSO users authenticate with IdP tokens instead
 * (see {@link OidcTokenAuthenticator}) and never hit this controller's password paths.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    /** Hash of a random password; matched against when the user does not exist so timing does not reveal valid user ids. */
    private static final String DUMMY_HASH = new BCryptPasswordEncoder().encode("constant-time-dummy-" + System.nanoTime());

    private final JdbcClient jdbc;
    private final JwtService jwt;
    private final ApiKeyService apiKeys;
    private final AuthorizationService authz;
    private final RateLimiter rateLimiter;
    private final AuditLog audit;
    private final PasswordPolicy passwordPolicy;
    private final SecurityProperties props;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthController(JdbcClient jdbc, JwtService jwt, ApiKeyService apiKeys, AuthorizationService authz, RateLimiter rateLimiter,
                          AuditLog audit, PasswordPolicy passwordPolicy, SecurityProperties props) {
        this.jdbc = jdbc;
        this.jwt = jwt;
        this.apiKeys = apiKeys;
        this.authz = authz;
        this.rateLimiter = rateLimiter;
        this.audit = audit;
        this.passwordPolicy = passwordPolicy;
        this.props = props;
    }

    public record LoginRequest(@NotBlank String tenantId, @NotBlank String userId, @NotBlank String password) {}
    public record LoginResponse(String token, Principal principal) {}

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        String ip = ClientIp.of(http, props.trustForwardedFor());
        String userKey = req.tenantId() + "/" + req.userId().toLowerCase(Locale.ROOT);
        rateLimiter.acquireOrThrow("auth.login.ip:" + ip, props.loginRateLimit(), props.loginRateWindow(), "login attempts from " + ip);
        rateLimiter.acquireOrThrow("auth.login.user:" + userKey, props.loginRateLimit(), props.loginRateWindow(), "login attempts for this user");

        var row = jdbc.sql("select password_hash, roles::text as roles from app_user where tenant_id = :t and user_id = :u and active")
            .param("t", req.tenantId()).param("u", req.userId()).query().listOfRows().stream().findFirst().orElse(null);
        String hash = row == null ? null : (String) row.get("password_hash");
        // Always run one bcrypt comparison so unknown users and wrong passwords take the same time.
        boolean ok = encoder.matches(req.password(), hash == null ? DUMMY_HASH : hash) && hash != null;
        if (!ok) {
            auditIn(req.tenantId(), "auth.login_failed", req.userId(), "Invalid credentials", Map.of("userId", req.userId(), "ip", ip));
            throw new UnauthorizedException("Invalid credentials");
        }
        Set<String> roles = Set.copyOf(Json.read((String) row.get("roles"), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {}));
        Principal p = new Principal(Principal.Type.USER, req.userId(), req.tenantId(), roles, Roles.permissionsFor(roles), 3);
        auditIn(req.tenantId(), "auth.login", req.userId(), null, Map.of("userId", req.userId(), "ip", ip, "roles", List.copyOf(roles)));
        return new LoginResponse(jwt.issue(p), p);
    }

    /** Stateless JWTs cannot be revoked server-side; the client discards the token. Kept so UIs have a uniform endpoint. */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout() {
        authz.currentPrincipal().ifPresent(p -> auditIn(p.tenantId(), "auth.logout", p.id(), null, Map.of()));
    }

    @GetMapping("/me")
    public Principal me() { return authz.requirePrincipal(); }

    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {}

    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody ChangePasswordRequest req) {
        Principal p = authz.requirePrincipal();
        if (p.type() != Principal.Type.USER) throw new ForbiddenException("Only user principals have passwords");
        String hash = jdbc.sql("select password_hash from app_user where tenant_id = :t and user_id = :u and active")
            .param("t", p.tenantId()).param("u", p.id()).query(String.class).optional()
            .orElseThrow(() -> new ForbiddenException("No local password for this user (SSO account?)"));
        if (hash == null || !encoder.matches(req.currentPassword(), hash)) {
            auditIn(p.tenantId(), "auth.password_change_failed", p.id(), "Current password mismatch", Map.of());
            throw new UnauthorizedException("Current password is incorrect");
        }
        passwordPolicy.validate(req.newPassword(), p.id());
        if (req.newPassword().equals(req.currentPassword())) throw new ValidationException("New password must differ from the current one");
        jdbc.sql("update app_user set password_hash = :h where tenant_id = :t and user_id = :u")
            .param("h", encoder.encode(req.newPassword())).param("t", p.tenantId()).param("u", p.id()).update();
        auditIn(p.tenantId(), "auth.password_changed", p.id(), null, Map.of());
    }

    public record CreateUserRequest(@NotBlank String userId, String displayName, @NotBlank String password, Set<String> roles) {}

    /**
     * Administrative user creation for deployments without dev users (production); passwords must satisfy the policy.
     * D-1: the caller may grant only roles whose permissions it holds, and never the platform role (403 otherwise).
     */
    @PostMapping("/users")
    @RequiresPermission(Permissions.CONFIG_ADMIN)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createUser(@Valid @RequestBody CreateUserRequest req) {
        String tenant = TenantContext.require();
        Set<String> roles = req.roles() == null ? Set.of() : req.roles();
        authz.requireMayGrant(roles, Set.of());
        passwordPolicy.validate(req.password(), req.userId());
        int n = jdbc.sql("""
            insert into app_user(tenant_id, user_id, display_name, password_hash, roles) values (:t, :u, :d, :h, :r)
            on conflict (tenant_id, user_id) do nothing
            """).param("t", tenant).param("u", req.userId()).param("d", req.displayName() == null ? req.userId() : req.displayName())
            .param("h", encoder.encode(req.password())).param("r", Jsonb.of(List.copyOf(roles))).update();
        if (n == 0) throw new ai.intentoms.platform.error.ConflictException("User already exists: " + req.userId());
        audit.record("auth.user_created", "app_user", req.userId(), null, Map.of("roles", List.copyOf(roles)));
        return Map.of("tenantId", tenant, "userId", req.userId(), "roles", roles);
    }

    public record IssueKeyRequest(String subjectId, Principal.Type type, Set<String> roles, Set<String> permissions, Integer trustTier, String label) {}

    /**
     * D-1: a key carries at most what its issuer holds: its roles' permissions and any explicit permissions must all be
     * held by the caller, the platform role only a platform operator may grant, and a key is a USER, AGENT or SYSTEM
     * (integration) principal, never a DECISION one.
     */
    @PostMapping("/api-keys")
    @RequiresPermission(Permissions.CONFIG_ADMIN)
    public Map<String, Object> issueKey(@RequestBody IssueKeyRequest req) {
        Set<String> roles = req.roles() == null ? Set.of() : req.roles();
        Principal.Type type = req.type() == null ? Principal.Type.USER : req.type();
        if (type == Principal.Type.DECISION) throw new ValidationException("An API key is a USER, AGENT or SYSTEM principal");
        authz.requireMayGrant(roles, req.permissions());
        Set<String> perms = req.permissions() == null ? Roles.permissionsFor(roles) : req.permissions();
        var issued = apiKeys.issue(TenantContext.require(), type, req.subjectId(), roles, perms,
            req.trustTier() == null ? 1 : req.trustTier(), req.label());
        audit.record("auth.api_key_issued", "api_key", issued.keyId(), req.label(), Map.of("subjectId", String.valueOf(req.subjectId()), "type", type.name(), "roles", List.copyOf(roles)));
        return Map.of("keyId", issued.keyId(), "apiKey", issued.plaintext(), "note", "Store this key now; it is not retrievable later.");
    }

    /** Revokes one of this tenant's keys; another tenant's key id is not found (404). */
    @DeleteMapping("/api-keys/{keyId}")
    @RequiresPermission(Permissions.CONFIG_ADMIN)
    public void revoke(@PathVariable String keyId) {
        if (!apiKeys.revoke(TenantContext.require(), keyId)) throw new ai.intentoms.platform.error.NotFoundException("No API key " + keyId + " in this tenant");
        audit.record("auth.api_key_revoked", "api_key", keyId, null, Map.of());
    }

    /** Audit inside the request's tenant; a failed login for an unknown tenant has nowhere to go and is only logged. */
    private void auditIn(String tenantId, String action, String userId, String reason, Map<String, Object> details) {
        try {
            TenantContext.runAs(tenantId, () -> audit.record(action, "app_user", userId, reason, details));
        } catch (DataAccessException e) {
            log.info("Audit {} for {}@{} not stored: {}", action, userId, tenantId, e.getMostSpecificCause().getMessage());
        }
    }
}
