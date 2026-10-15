package ai.intentoms.agent.mandate;

import ai.intentoms.agent.auth.HmacSigner;
import ai.intentoms.finance.model.Money;
import ai.intentoms.platform.json.Json;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Mandate signature verification (AP2-style, FR-CAP-07, FR-FIN-06). The signed message is the canonical (sorted-key)
 * JSON of {@code {buyerRef, scope, spendLimit, instructionText, expiresAt}}. When the agent registered a public key the
 * signature is Ed25519 or ECDSA-P256/SHA-256 over that message (base64 or hex); otherwise it is HMAC-SHA256 under the
 * agent's secret (hex, optional {@code sha256=} prefix).
 */
public final class MandateSigner {
    private MandateSigner() {}

    public static Map<String, Object> canonicalFields(String buyerRef, MandateScope scope, Money spendLimit, String instructionText, Instant expiresAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("buyerRef", buyerRef);
        m.put("scope", scope == null ? MandateScope.empty() : scope);
        m.put("spendLimit", spendLimit == null ? null : Map.of("amount", spendLimit.amount().toPlainString(), "currency", spendLimit.currency()));
        m.put("instructionText", instructionText);
        m.put("expiresAt", expiresAt == null ? null : expiresAt.toString());
        return m;
    }

    public static String canonicalJson(String buyerRef, MandateScope scope, Money spendLimit, String instructionText, Instant expiresAt) {
        return Json.canonical(canonicalFields(buyerRef, scope, spendLimit, instructionText, expiresAt));
    }

    public static String hmacSign(String secret, String canonicalJson) { return HmacSigner.hmacHex(secret, canonicalJson); }

    public static boolean hmacVerify(String secret, String canonicalJson, String signature) {
        if (secret == null || signature == null) return false;
        String s = signature.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("sha256=")) s = s.substring(7);
        return MessageDigest.isEqual(hmacSign(secret, canonicalJson).getBytes(StandardCharsets.UTF_8), s.getBytes(StandardCharsets.UTF_8));
    }

    /** Parses a base64 (optionally PEM-wrapped) X.509 SubjectPublicKeyInfo as Ed25519 or EC. */
    public static PublicKey parsePublicKey(String encoded) {
        String b64 = encoded.replaceAll("-----(BEGIN|END)[A-Z ]*-----", "").replaceAll("\\s", "");
        byte[] der;
        try { der = Base64.getDecoder().decode(b64); } catch (IllegalArgumentException e) { throw new IllegalArgumentException("public key is not base64", e); }
        X509EncodedKeySpec spec = new X509EncodedKeySpec(der);
        for (String alg : new String[] {"Ed25519", "EC"}) {
            try { return KeyFactory.getInstance(alg).generatePublic(spec); } catch (GeneralSecurityException ignored) { }
        }
        throw new IllegalArgumentException("public key is neither Ed25519 nor EC");
    }

    public static boolean publicKeyVerify(PublicKey key, String canonicalJson, String signature) {
        if (key == null || signature == null) return false;
        byte[] sig = decodeSignature(signature);
        if (sig == null) return false;
        try {
            Signature v = Signature.getInstance("Ed25519".equals(key.getAlgorithm()) || "EdDSA".equals(key.getAlgorithm()) ? "Ed25519" : "SHA256withECDSA");
            v.initVerify(key);
            v.update(canonicalJson.getBytes(StandardCharsets.UTF_8));
            return v.verify(sig);
        } catch (GeneralSecurityException e) { return false; }
    }

    private static byte[] decodeSignature(String signature) {
        String s = signature.trim();
        try { return Base64.getDecoder().decode(s); } catch (IllegalArgumentException ignored) { }
        try { return Base64.getUrlDecoder().decode(s); } catch (IllegalArgumentException ignored) { }
        try { return HexFormat.of().parseHex(s.toLowerCase(Locale.ROOT)); } catch (IllegalArgumentException ignored) { }
        return null;
    }
}
