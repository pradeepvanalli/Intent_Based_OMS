package ai.intentoms.integrations.payments;

import ai.intentoms.integrations.config.HmacSignatures;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * Stripe's webhook signature scheme: header {@code Stripe-Signature: t=<unix seconds>,v1=<hex hmac>[,v1=...]}, where
 * the HMAC-SHA256 is computed with the endpoint secret over {@code "<t>.<raw body>"}. A signature older than the
 * tolerance is rejected so a captured request cannot be replayed later.
 */
public final class StripeSignatures {
    private StripeSignatures() {}

    public static final long DEFAULT_TOLERANCE_SECONDS = 300;

    public static boolean matches(String secret, String rawBody, String header, Instant now, long toleranceSeconds) {
        if (secret == null || header == null || header.isBlank()) return false;
        Long t = null;
        java.util.List<String> v1 = new java.util.ArrayList<>();
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) continue;
            if ("t".equals(kv[0])) { try { t = Long.parseLong(kv[1]); } catch (NumberFormatException e) { return false; } }
            else if ("v1".equals(kv[0])) v1.add(kv[1]);
        }
        if (t == null || v1.isEmpty()) return false;
        if (Math.abs(now.getEpochSecond() - t) > toleranceSeconds) return false;
        byte[] expected = HmacSignatures.hex(secret, t + "." + (rawBody == null ? "" : rawBody)).getBytes(StandardCharsets.UTF_8);
        for (String sig : v1) if (MessageDigest.isEqual(expected, sig.toLowerCase().getBytes(StandardCharsets.UTF_8))) return true;
        return false;
    }

    /** The header value Stripe would send for this body at {@code t} (tests and local tooling). */
    public static String header(String secret, String rawBody, long t) {
        return "t=" + t + ",v1=" + HmacSignatures.hex(secret, t + "." + rawBody);
    }
}
