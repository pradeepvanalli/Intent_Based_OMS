package ai.intentoms.agent.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Request signing for agents (FR-AGT-03, GR-14 replay window). The string to sign is
 * {@code timestamp + "\n" + METHOD + "\n" + path + "\n" + sha256hex(body)} and the signature is lower-case hex
 * HMAC-SHA256 under the agent secret. Headers: {@code X-Agent-Id}, {@code X-Agent-Timestamp} (epoch seconds or ISO-8601),
 * {@code X-Agent-Signature}. {@code path} is the request path without query string.
 */
public final class HmacSigner {
    public static final String HEADER_AGENT = "X-Agent-Id";
    public static final String HEADER_TIMESTAMP = "X-Agent-Timestamp";
    public static final String HEADER_SIGNATURE = "X-Agent-Signature";
    private static final HexFormat HEX = HexFormat.of();

    private HmacSigner() {}

    public static String sha256Hex(byte[] body) { return HEX.formatHex(SecretCrypto.sha256(body == null ? new byte[0] : body)); }

    public static String sha256Hex(String body) { return sha256Hex(body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8)); }

    public static String stringToSign(String timestamp, String method, String path, String bodySha256) {
        return timestamp + "\n" + method.toUpperCase(Locale.ROOT) + "\n" + path + "\n" + bodySha256;
    }

    public static String sign(String secret, String timestamp, String method, String path, String bodySha256) {
        return hmacHex(secret, stringToSign(timestamp, method, path, bodySha256));
    }

    /** Convenience for clients: signs a full request. */
    public static String sign(String secret, String timestamp, String method, String path, byte[] body) {
        return sign(secret, timestamp, method, path, sha256Hex(body));
    }

    public static boolean verify(String secret, String timestamp, String method, String path, String bodySha256, String presented) {
        if (secret == null || presented == null) return false;
        String expected = sign(secret, timestamp, method, path, bodySha256);
        String given = presented.trim().toLowerCase(Locale.ROOT);
        if (given.startsWith("sha256=")) given = given.substring(7);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
    }

    public static String hmacHex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HEX.formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** Parses the timestamp header: digits are epoch seconds (or millis when > 10^11), anything else ISO-8601. Null when unparseable. */
    public static Instant parseTimestamp(String ts) {
        if (ts == null || ts.isBlank()) return null;
        String t = ts.trim();
        if (t.chars().allMatch(Character::isDigit)) {
            long v = Long.parseLong(t);
            return v > 100_000_000_000L ? Instant.ofEpochMilli(v) : Instant.ofEpochSecond(v);
        }
        try { return Instant.parse(t); } catch (DateTimeParseException e) { return null; }
    }

    public static boolean withinWindow(String timestamp, Instant now, Duration window) {
        Instant t = parseTimestamp(timestamp);
        if (t == null) return false;
        return Math.abs(Duration.between(t, now).toMillis()) <= window.toMillis();
    }
}
