package ai.intentoms.order.tracking;

import ai.intentoms.platform.security.SecurityProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Stateless, URL-safe tracking tokens for the public order tracking page (no table): {@code base64url(tenant|order|exp)}
 * {@code .} {@code base64url(HMAC-SHA256(secret, payload))}, signed with the deployment's JWT secret
 * ({@code oms.security.jwt-secret}). A token names exactly one order of one tenant and expires after {@link #TTL}.
 */
@Service
public class TrackingTokenService {
    public static final Duration TTL = Duration.ofDays(30);
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();

    public record Claims(String tenantId, String orderId, Instant expiresAt) {}
    public record Issued(String token, Instant expiresAt) {}

    private final byte[] key;

    public TrackingTokenService(SecurityProperties props) {
        this.key = ("tracking:" + props.effectiveJwtSecret()).getBytes(StandardCharsets.UTF_8);
    }

    public Issued issue(String tenantId, String orderId, Instant now) {
        if (tenantId == null || tenantId.isBlank() || orderId == null || orderId.isBlank()) throw new IllegalArgumentException("tenantId and orderId are required");
        if (tenantId.indexOf('|') >= 0 || orderId.indexOf('|') >= 0) throw new IllegalArgumentException("tenantId and orderId must not contain '|'");
        Instant exp = now.plus(TTL).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        String payload = tenantId + "|" + orderId + "|" + exp.getEpochSecond();
        String p = ENC.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return new Issued(p + "." + ENC.encodeToString(sign(p)), exp);
    }

    /** Empty when the token is malformed, its signature does not match, or it has expired. */
    public Optional<Claims> verify(String token, Instant now) {
        if (token == null || token.length() > 512) return Optional.empty();
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) return Optional.empty();
        String p = token.substring(0, dot);
        byte[] sig;
        byte[] payload;
        try {
            sig = DEC.decode(token.substring(dot + 1));
            payload = DEC.decode(p);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(sign(p), sig)) return Optional.empty();
        String[] parts = new String(payload, StandardCharsets.UTF_8).split("\\|", -1);
        if (parts.length != 3) return Optional.empty();
        Instant exp;
        try { exp = Instant.ofEpochSecond(Long.parseLong(parts[2])); } catch (NumberFormatException e) { return Optional.empty(); }
        if (parts[0].isBlank() || parts[1].isBlank() || !exp.isAfter(now)) return Optional.empty();
        return Optional.of(new Claims(parts[0], parts[1], exp));
    }

    private byte[] sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
