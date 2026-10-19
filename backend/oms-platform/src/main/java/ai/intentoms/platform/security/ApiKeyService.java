package ai.intentoms.platform.security;

import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.json.Json;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;

/**
 * API keys for users, integrations and agents. Only the SHA-256 of the key is stored.
 * Key format: {@code oms_<keyId>_<secret>} so the id can be looked up without scanning.
 */
@Service
public class ApiKeyService {
    private final JdbcClient jdbc;
    private final SecureRandom random = new SecureRandom();

    public ApiKeyService(JdbcClient jdbc) { this.jdbc = jdbc; }

    public record Issued(String keyId, String plaintext) {}

    public Issued issue(String tenantId, Principal.Type type, String subjectId, Set<String> roles, Set<String> permissions, int trustTier, String label) {
        String keyId = Ids.shortId();
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String plaintext = "oms_" + keyId + "_" + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        jdbc.sql("""
            insert into api_key(key_id, tenant_id, key_hash, principal_type, subject_id, roles, permissions, trust_tier, label)
            values (:keyId, :tenant, :hash, :type, :subject, :roles::jsonb, :perms::jsonb, :tier, :label)
            """)
            .param("keyId", keyId).param("tenant", tenantId).param("hash", sha256(plaintext))
            .param("type", type.name()).param("subject", subjectId)
            .param("roles", Json.write(roles)).param("perms", Json.write(permissions))
            .param("tier", trustTier).param("label", label)
            .update();
        return new Issued(keyId, plaintext);
    }

    public Optional<Principal> authenticate(String plaintext) {
        if (plaintext == null || !plaintext.startsWith("oms_")) return Optional.empty();
        String[] parts = plaintext.split("_", 3);
        if (parts.length != 3) return Optional.empty();
        return jdbc.sql("select tenant_id, key_hash, principal_type, subject_id, roles::text, permissions::text, trust_tier from api_key where key_id = :k and revoked_at is null")
            .param("k", parts[1])
            .query((rs, i) -> {
                if (!rs.getString("key_hash").equals(sha256(plaintext))) return null;
                return new Principal(Principal.Type.valueOf(rs.getString("principal_type")), rs.getString("subject_id"), rs.getString("tenant_id"),
                    Json.readSet(rs.getString("roles")), Json.readSet(rs.getString("permissions")), rs.getInt("trust_tier"));
            })
            .optional().filter(p -> p != null);
    }

    public void revoke(String keyId) {
        jdbc.sql("update api_key set revoked_at = now() where key_id = :k").param("k", keyId).update();
    }

    /** Revokes a key of {@code tenantId} only; false when that tenant has no such key (the API's revoke path). */
    public boolean revoke(String tenantId, String keyId) {
        return jdbc.sql("update api_key set revoked_at = coalesce(revoked_at, now()) where key_id = :k and tenant_id = :t")
            .param("k", keyId).param("t", tenantId).update() > 0;
    }

    public static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
