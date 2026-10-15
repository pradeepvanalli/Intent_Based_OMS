package ai.intentoms.agent.registry;

import java.time.Instant;

/**
 * A registered agent (FR-AGT-03, section 10 "AgentIdentity"): identity, owner, protocol, verification method, trust tier
 * and limits. Matches the UI's {@code AgentIdentity} ({@code active}, {@code verification}, {@code orders30d}). Secrets
 * never leave the registry through this type.
 *
 * @param verification {@code public_key} when the agent registered a key for signed mandates, else {@code hmac}
 * @param trustTier    0 unknown/read-only, 1 verified, 2 trusted partner, 3 internal
 */
public record AgentIdentity(String id, String owner, String protocol, String verification, int trustTier, AgentLimits limits, boolean active, String status,
                            String callbackUrl, boolean hasPublicKey, String apiKeyId, Instant createdAt, Instant updatedAt, Instant lastSeenAt, Integer orders30d) {
    public boolean hasCallback() { return callbackUrl != null && !callbackUrl.isBlank(); }
}
