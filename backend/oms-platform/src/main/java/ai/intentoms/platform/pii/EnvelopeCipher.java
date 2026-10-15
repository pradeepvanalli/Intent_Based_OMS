package ai.intentoms.platform.pii;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * AES-256-GCM envelope encryption for key material at rest (NFR-08). Ciphertext format: {@code enc:<base64(iv || ciphertext+tag)>}
 * with a 12-byte random IV and 128-bit tag; the string "tenant_key" is bound as associated data. Values without the
 * {@code enc:} prefix are legacy plaintext (base64 of the raw key) and are accepted for backwards compatibility.
 * <p>
 * The master key is {@code oms.security.master-key}: either base64 of exactly 32 bytes or any passphrase, which is
 * SHA-256 hashed to 32 bytes. Rotating the master key requires re-wrapping every row (see HARDENING.md).
 */
public final class EnvelopeCipher {
    public static final String PREFIX = "enc:";
    private static final String AAD = "tenant_key";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    private EnvelopeCipher(byte[] keyBytes) { this.key = new SecretKeySpec(keyBytes, "AES"); }

    /** Empty when no master key is configured. */
    public static Optional<EnvelopeCipher> fromMasterKey(String masterKey) {
        if (masterKey == null || masterKey.isBlank()) return Optional.empty();
        return Optional.of(new EnvelopeCipher(deriveKey(masterKey.trim())));
    }

    static byte[] deriveKey(String masterKey) {
        try {
            byte[] decoded = Base64.getDecoder().decode(masterKey);
            if (decoded.length == 32) return decoded;
        } catch (IllegalArgumentException ignored) { /* not base64: treat as passphrase */ }
        try {
            return MessageDigest.getInstance("SHA-256").digest(masterKey.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static boolean isEncrypted(String stored) { return stored != null && stored.startsWith(PREFIX); }

    public String encrypt(byte[] plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(AAD.getBytes(StandardCharsets.UTF_8));
            byte[] ct = c.doFinal(plaintext);
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) { throw new IllegalStateException("Envelope encryption failed", e); }
    }

    public byte[] decrypt(String stored) {
        if (!isEncrypted(stored)) throw new IllegalArgumentException("Value is not envelope-encrypted");
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            c.updateAAD(AAD.getBytes(StandardCharsets.UTF_8));
            return c.doFinal(all, IV_BYTES, all.length - IV_BYTES);
        } catch (Exception e) { throw new IllegalStateException("Envelope decryption failed (wrong oms.security.master-key?)", e); }
    }
}
