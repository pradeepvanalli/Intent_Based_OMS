package ai.intentoms.agent.auth;

import ai.intentoms.agent.config.AgentProperties;
import ai.intentoms.platform.security.SecurityProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * At-rest protection of agent HMAC secrets (FR-AGT-03). Request signatures can only be verified with the secret itself,
 * so it is stored AES-256-GCM encrypted (key = SHA-256 of {@code oms.agent.secret-key}, falling back to the JWT secret)
 * next to its SHA-256 hash. Format: base64url(iv[12] || ciphertext||tag).
 */
@Component
public class SecretCrypto {
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCrypto(AgentProperties props, SecurityProperties security) {
        String material = props.secretKey() == null || props.secretKey().isBlank() ? security.jwtSecret() : props.secretKey();
        this.key = new SecretKeySpec(sha256(material.getBytes(StandardCharsets.UTF_8)), "AES");
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (GeneralSecurityException e) { throw new IllegalStateException("Cannot encrypt agent secret", e); }
    }

    public String decrypt(String encoded) {
        try {
            byte[] all = Base64.getUrlDecoder().decode(encoded);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return new String(c.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) { throw new IllegalStateException("Cannot decrypt agent secret", e); }
    }

    public static byte[] sha256(byte[] in) {
        try { return MessageDigest.getInstance("SHA-256").digest(in); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
}
