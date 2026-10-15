package ai.intentoms.integrations.config;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

/**
 * HMAC-SHA256 signing and constant-time verification shared by the inbound webhooks (marketplace, EasyPost) and the
 * outbound WMS documents. Accepted header encodings: hex, base64, and the prefixed forms {@code sha256=<hex>} and
 * {@code hmac-sha256-hex=<hex>} (EasyPost).
 */
public final class HmacSignatures {
    private HmacSignatures() {}

    public static byte[] hmac(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal((body == null ? "" : body).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static String hex(String secret, String body) { return HexFormat.of().formatHex(hmac(secret, body)); }

    public static String base64(String secret, String body) { return Base64.getEncoder().encodeToString(hmac(secret, body)); }

    /** True when {@code signature} is the HMAC of {@code body} under {@code secret} in any accepted encoding. */
    public static boolean matches(String secret, String body, String signature) {
        if (signature == null || signature.isBlank()) return false;
        String sig = signature.trim();
        int eq = sig.indexOf('=');
        if (eq > 0 && (sig.regionMatches(true, 0, "sha256=", 0, 7) || sig.regionMatches(true, 0, "hmac-sha256-hex=", 0, 16))) sig = sig.substring(eq + 1);
        byte[] mac = hmac(secret, body);
        byte[] hex = HexFormat.of().formatHex(mac).getBytes(StandardCharsets.UTF_8);
        byte[] b64 = Base64.getEncoder().encodeToString(mac).getBytes(StandardCharsets.UTF_8);
        byte[] given = sig.getBytes(StandardCharsets.UTF_8);
        byte[] givenLower = sig.toLowerCase().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(hex, givenLower) || MessageDigest.isEqual(b64, given);
    }
}
