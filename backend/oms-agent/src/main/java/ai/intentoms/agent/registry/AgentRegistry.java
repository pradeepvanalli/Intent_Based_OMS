package ai.intentoms.agent.registry;

import ai.intentoms.agent.auth.SecretCrypto;
import ai.intentoms.platform.audit.AuditLog;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
import ai.intentoms.platform.security.ApiKeyService;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.security.Principal;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Agent registry (FR-AGT-03, FR-AGT-04): identity, verification material, owner, trust tier and limits. Registration
 * issues an HMAC secret (returned once, stored encrypted + hashed) and a platform API key whose roles follow the trust
 * tier (0 agent_readonly, 1–2 agent_shopper, 3 agent_ops) and whose permissions follow {@code allowedActions}. The
 * registry is the single source of truth for an agent's effective permissions: {@code AgentAuthFilter} rebuilds the
 * principal from the current row on every request, so changing limits takes effect immediately (no model or agent can
 * change limits, GR-14: writes need {@code agent.admin}).
 */
@Service
public class AgentRegistry {
    public static final String AGGREGATE = "agent";
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{1,63}");
    private static final Set<String> PROTOCOLS = Set.of("ucp", "acp", "mcp");

    private final AgentRepository repo;
    private final ApiKeyService apiKeys;
    private final SecretCrypto crypto;
    private final EventStore events;
    private final AuditLog audit;
    private final AuthorizationService authz;
    private final SecureRandom random = new SecureRandom();

    public AgentRegistry(AgentRepository repo, ApiKeyService apiKeys, SecretCrypto crypto, EventStore events, AuditLog audit, AuthorizationService authz) {
        this.repo = repo;
        this.apiKeys = apiKeys;
        this.crypto = crypto;
        this.events = events;
        this.audit = audit;
        this.authz = authz;
    }

    @Transactional
    public AgentRegistered register(AgentRegistration r) {
        if (r == null || r.id() == null || !ID.matcher(r.id()).matches()) throw new ValidationException("agent id must match " + ID.pattern());
        if (r.owner() == null || r.owner().isBlank()) throw new ValidationException("owner is required");
        String protocol = r.protocol() == null ? "mcp" : r.protocol().trim().toLowerCase(Locale.ROOT);
        if (!PROTOCOLS.contains(protocol)) throw new ValidationException("protocol must be one of " + PROTOCOLS);
        int tier = RolesByTier.clamp(r.trustTier() == null ? 1 : r.trustTier());
        if (r.trustTier() != null && (r.trustTier() < 0 || r.trustTier() > 3)) throw new ValidationException("trustTier must be 0..3");
        String tenant = TenantContext.require();
        if (repo.find(r.id()).isPresent()) throw new ConflictException("Agent " + r.id() + " already exists", Map.of("agentId", r.id()));
        AgentLimits limits = r.limits() == null ? new AgentLimits(List.of("order_get"), null, null, 60, List.of()) : r.limits();
        validatePublicKey(r.publicKey());
        requireMayGrant(limits);

        String secret = newSecret();
        ApiKeyService.Issued key = apiKeys.issue(tenant, Principal.Type.AGENT, r.id(), RolesByTier.roles(tier), ToolPermissions.permissionsFor(limits.allowedActions()), tier, "agent:" + r.id());
        AgentRepository.Row row = new AgentRepository.Row(tenant, r.id(), r.owner().trim(), protocol, tier, sha256Hex(secret), crypto.encrypt(secret), blankToNull(r.publicKey()),
            key.keyId(), limits, blankToNull(r.callbackUrl()), "active", null, null, null);
        repo.insert(row);
        append(r.id(), "agent.registered", Map.of("agentId", r.id(), "owner", row.owner(), "protocol", protocol, "trustTier", tier, "allowedActions", limits.allowedActions(), "apiKeyId", key.keyId()));
        audit.record("agent.register", "agent", r.id(), "registration", Map.of("protocol", protocol, "trustTier", tier));
        return new AgentRegistered(toIdentity(repo.find(r.id()).orElseThrow(), false), secret, key.plaintext(), key.keyId(),
            "Store the secret and API key now; they are not retrievable later. Rotate with POST /api/v1/agents/{id}/rotate-secret.");
    }

    public AgentIdentity get(String agentId) { return find(agentId).orElseThrow(() -> new NotFoundException("Agent " + agentId + " not found")); }

    public Optional<AgentIdentity> find(String agentId) { return repo.find(agentId).map(r -> toIdentity(r, false)); }

    public List<AgentIdentity> list() { return repo.list().stream().map(r -> toIdentity(r, true)).toList(); }

    @Transactional
    public AgentIdentity update(String agentId, AgentUpdate u) {
        AgentRepository.Row cur = row(agentId);
        int tier = u.trustTier() == null ? cur.trustTier() : u.trustTier();
        if (tier < 0 || tier > 3) throw new ValidationException("trustTier must be 0..3");
        validatePublicKey(u.publicKey());
        AgentLimits limits = cur.limits().merge(u.limits());
        requireMayGrant(limits);
        String status = u.active() == null ? cur.status() : (u.active() ? "active" : "suspended");
        AgentRepository.Row next = new AgentRepository.Row(cur.tenantId(), cur.agentId(), u.owner() == null || u.owner().isBlank() ? cur.owner() : u.owner().trim(), cur.protocol(), tier,
            cur.secretHash(), cur.secretEnc(), u.publicKey() == null ? cur.publicKey() : blankToNull(u.publicKey()), cur.apiKeyId(), limits,
            u.callbackUrl() == null ? cur.callbackUrl() : blankToNull(u.callbackUrl()), status, cur.createdAt(), cur.updatedAt(), cur.lastSeenAt());
        repo.update(next);
        append(agentId, "agent.updated", Map.of("agentId", agentId, "trustTier", tier, "allowedActions", limits.allowedActions(), "confirmActions", limits.confirmActions(), "status", status));
        audit.record("agent.update", "agent", agentId, "update", Map.of("trustTier", tier, "status", status));
        return toIdentity(repo.find(agentId).orElseThrow(), false);
    }

    /** Issues a new HMAC secret; the old one stops verifying immediately. Returned once. */
    @Transactional
    public Map<String, String> rotateSecret(String agentId) {
        AgentRepository.Row cur = row(agentId);
        String secret = newSecret();
        repo.update(new AgentRepository.Row(cur.tenantId(), cur.agentId(), cur.owner(), cur.protocol(), cur.trustTier(), sha256Hex(secret), crypto.encrypt(secret), cur.publicKey(),
            cur.apiKeyId(), cur.limits(), cur.callbackUrl(), cur.status(), cur.createdAt(), cur.updatedAt(), cur.lastSeenAt()));
        append(agentId, "agent.secret_rotated", Map.of("agentId", agentId));
        audit.record("agent.rotate_secret", "agent", agentId, "rotation", Map.of());
        return Map.of("agentId", agentId, "secret", secret);
    }

    @Transactional
    public AgentIdentity suspend(String agentId, String reason) {
        AgentRepository.Row cur = row(agentId);
        repo.update(new AgentRepository.Row(cur.tenantId(), cur.agentId(), cur.owner(), cur.protocol(), cur.trustTier(), cur.secretHash(), cur.secretEnc(), cur.publicKey(),
            cur.apiKeyId(), cur.limits(), cur.callbackUrl(), "suspended", cur.createdAt(), cur.updatedAt(), cur.lastSeenAt()));
        append(agentId, "agent.suspended", Map.of("agentId", agentId, "reason", reason == null ? "" : reason));
        audit.record("agent.suspend", "agent", agentId, reason, Map.of());
        return toIdentity(repo.find(agentId).orElseThrow(), false);
    }

    public void recordActivity(String agentId, String tool, String outcome, String orderId, long latencyMs) {
        recordActivity(agentId, tool, outcome, orderId, latencyMs, Map.of());
    }

    public void recordActivity(String agentId, String tool, String outcome, String orderId, long latencyMs, Map<String, Object> details) {
        repo.recordActivity(agentId, tool, outcome, orderId, latencyMs, details);
    }

    public List<AgentActivity> activity(String agentId, int limit) { row(agentId); return repo.activity(agentId, limit); }

    public void touch(String agentId) { repo.touch(agentId); }

    /** Tenants that registered this agent id; used by the signing filter when no tenant header is present. */
    public List<String> tenantsOf(String agentId) { return repo.tenantsOf(agentId); }

    /** The agent's current HMAC secret (request signatures, mandate HMACs, webhook signatures). Internal use only. */
    public String hmacSecret(String agentId) { return crypto.decrypt(row(agentId).secretEnc()); }

    public Optional<String> publicKey(String agentId) { return repo.find(agentId).map(AgentRepository.Row::publicKey).filter(k -> k != null && !k.isBlank()); }

    /** Builds the principal an agent acts as: roles by tier, permissions from its allowed tools (FR-AGT-03/04/06). */
    public Principal principalFor(AgentIdentity a) {
        Set<String> roles = RolesByTier.roles(a.trustTier());
        return new Principal(Principal.Type.AGENT, a.id(), TenantContext.require(), roles, ToolPermissions.permissionsFor(a.limits().allowedActions()), a.trustTier());
    }

    /**
     * D-1: a person registering or changing an agent may give it only tools whose permissions they hold themselves.
     * Agents loaded from tenant configuration at startup run with no principal and are reviewed config, not a grant.
     */
    private void requireMayGrant(AgentLimits limits) {
        if (authz.currentPrincipal().isPresent()) authz.requireMayGrant(Set.of(), ToolPermissions.permissionsFor(limits.allowedActions()));
    }

    private AgentRepository.Row row(String agentId) { return repo.find(agentId).orElseThrow(() -> new NotFoundException("Agent " + agentId + " not found")); }

    private AgentIdentity toIdentity(AgentRepository.Row r, boolean withVolume) {
        boolean pk = r.publicKey() != null && !r.publicKey().isBlank();
        return new AgentIdentity(r.agentId(), r.owner(), r.protocol(), pk ? "public_key" : "hmac", r.trustTier(), r.limits(), r.active(), r.status(), r.callbackUrl(), pk,
            r.apiKeyId(), r.createdAt(), r.updatedAt(), r.lastSeenAt(), withVolume ? repo.orders30d(r.agentId()) : null);
    }

    private void append(String agentId, String type, Map<String, Object> payload) {
        Actor actor = authz.currentPrincipal().map(p -> Actor.parse(p.actorRef())).orElse(Actor.system("agent-registry"));
        events.append(AGGREGATE, agentId, new NewEvent(type, new LinkedHashMap<>(payload), actor, null, agentId, Map.of()));
    }

    private String newSecret() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return "ags_" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String sha256Hex(String s) { return HexFormat.of().formatHex(SecretCrypto.sha256(s.getBytes(StandardCharsets.UTF_8))); }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    private static void validatePublicKey(String key) {
        if (key == null || key.isBlank()) return;
        try { ai.intentoms.agent.mandate.MandateSigner.parsePublicKey(key); }
        catch (IllegalArgumentException e) { throw new ValidationException("publicKey is not a valid Ed25519/EC SubjectPublicKeyInfo: " + e.getMessage()); }
    }
}
