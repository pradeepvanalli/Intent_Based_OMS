package ai.intentoms.platform.pii;

import ai.intentoms.platform.security.SecurityProperties;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deterministic, per-tenant PII tokenisation (GR-09, NFR-08). Names, emails and addresses become stable tokens such
 * as "em_3f9a..." before they reach any model provider; the same input yields the same token so history questions
 * ("more than two not-received claims") still work. Deleting the tenant key crypto-shreds every token (CCPA deletion).
 * <p>
 * Key material lives in {@code tenant_key(purpose='pii')}. When {@code oms.security.master-key} is set the row is
 * envelope-encrypted with AES-GCM ({@link EnvelopeCipher}, {@code enc:} prefix); legacy plaintext rows (base64) are
 * still readable and are re-wrapped in place the first time they are loaded, so enabling the master key needs no migration.
 * Without a master key the raw key is stored base64-encoded (a warning is logged; production refuses to start).
 */
@Component
public class Tokenizer {
    private static final Logger log = LoggerFactory.getLogger(Tokenizer.class);
    private final JdbcClient jdbc;
    private final Optional<EnvelopeCipher> envelope;
    private final Map<String, byte[]> keys = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    @org.springframework.beans.factory.annotation.Autowired
    public Tokenizer(JdbcClient jdbc, SecurityProperties props) { this(jdbc, props.masterKey()); }

    /** No envelope encryption (legacy callers and unit-test fakes). */
    public Tokenizer(JdbcClient jdbc) { this(jdbc, (String) null); }

    private Tokenizer(JdbcClient jdbc, String masterKey) {
        this.jdbc = jdbc;
        this.envelope = EnvelopeCipher.fromMasterKey(masterKey);
        if (envelope.isEmpty()) {
            log.warn("oms.security.master-key is not set: tenant PII keys are stored unwrapped in tenant_key. Set a master key before production (the prod readiness check enforces this).");
        }
    }

    public boolean envelopeEnabled() { return envelope.isPresent(); }

    public String token(String kind, String value) {
        if (value == null || value.isBlank()) return null;
        byte[] key = keyFor(TenantContext.require());
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] d = mac.doFinal((kind + ":" + value.trim().toLowerCase()).getBytes(StandardCharsets.UTF_8));
            return kind + "_" + HexFormat.of().formatHex(d, 0, 10);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public String email(String v) { return token("em", v); }
    public String name(String v) { return token("nm", v); }
    public String phone(String v) { return token("ph", v); }
    public String address(String v) { return token("ad", v); }

    private byte[] keyFor(String tenant) {
        return keys.computeIfAbsent(tenant, t -> {
            var existing = loadActive(t);
            if (existing.isPresent()) return unwrap(t, existing.get());
            byte[] k = new byte[32];
            random.nextBytes(k);
            jdbc.sql("insert into tenant_key(tenant_id, purpose, key_material) values (:t, 'pii', :k) on conflict do nothing").param("t", t).param("k", wrap(k)).update();
            return unwrap(t, loadActive(t).orElseThrow());
        });
    }

    private Optional<String> loadActive(String tenant) {
        return jdbc.sql("select key_material from tenant_key where tenant_id = :t and purpose = 'pii' and active").param("t", tenant).query(String.class).optional();
    }

    private String wrap(byte[] raw) {
        return envelope.map(e -> e.encrypt(raw)).orElseGet(() -> Base64.getEncoder().encodeToString(raw));
    }

    /** Decode a stored value; plaintext legacy rows are upgraded to the envelope format when a master key is available. */
    private byte[] unwrap(String tenant, String stored) {
        if (EnvelopeCipher.isEncrypted(stored)) {
            return envelope.orElseThrow(() -> new IllegalStateException("tenant_key for " + tenant + " is envelope-encrypted but oms.security.master-key is not set")).decrypt(stored);
        }
        byte[] raw = Base64.getDecoder().decode(stored);
        envelope.ifPresent(e -> {
            int n = jdbc.sql("update tenant_key set key_material = :enc where tenant_id = :t and purpose = 'pii' and active and key_material = :old")
                .param("enc", e.encrypt(raw)).param("t", tenant).param("old", stored).update();
            if (n > 0) log.info("Envelope-encrypted PII key for tenant {}", tenant);
        });
        return raw;
    }

    /** Crypto-shred: rotate the key so all historical tokens become unlinkable. */
    public void shred(String tenant) {
        jdbc.sql("update tenant_key set active = false, retired_at = now() where tenant_id = :t and purpose = 'pii'").param("t", tenant).update();
        keys.remove(tenant);
    }
}
