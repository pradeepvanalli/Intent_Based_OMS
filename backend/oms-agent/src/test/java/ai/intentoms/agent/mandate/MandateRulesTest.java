package ai.intentoms.agent.mandate;

import ai.intentoms.finance.model.Money;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-CAP-07 / FR-FIN-06: mandate verification rules, signatures and scope parsing. */
class MandateRulesTest {
    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

    private static Mandate mandate(String status, Instant expires, String limit, String buyer, Boolean sigValid) {
        return new Mandate("mnd_1", "shopbot", buyer, new MandateScope("demo", List.of("apparel"), 3), Money.usd(limit), "Buy two tees", "sig", sigValid, "tok", 0.05, null,
            NOW.minusSeconds(60), expires, status, List.of(), NOW, NOW);
    }

    @Test
    void validMandatePasses() {
        MandateCheck c = MandateService.check(mandate("active", NOW.plusSeconds(3600), "100.00", "buyer-1", true), "shopbot", Money.usd("99.99"), "buyer-1", NOW);
        assertThat(c.valid()).isTrue();
        assertThat(c.reasons()).isEmpty();
        assertThat(c.mandate().id()).isEqualTo("mnd_1");
    }

    @Test
    void collectsEveryFailingRule() {
        MandateCheck c = MandateService.check(mandate("active", NOW.minusSeconds(1), "50.00", "buyer-1", false), "other-agent", Money.usd("60.00"), "buyer-2", NOW);
        assertThat(c.valid()).isFalse();
        assertThat(c.reasons()).containsExactlyInAnyOrder(MandateService.REASON_AGENT_MISMATCH, MandateService.REASON_EXPIRED, MandateService.REASON_SPEND_LIMIT,
            MandateService.REASON_BUYER, MandateService.REASON_SIGNATURE);
    }

    @Test
    void consumedRevokedAndCurrencyRules() {
        assertThat(MandateService.check(mandate("consumed", NOW.plusSeconds(10), "100", null, null), "shopbot", Money.usd("10"), null, NOW).reasons()).containsExactly(MandateService.REASON_NOT_ACTIVE);
        assertThat(MandateService.check(mandate("revoked", NOW.plusSeconds(10), "100", null, null), "shopbot", Money.usd("10"), null, NOW).reasons()).containsExactly(MandateService.REASON_NOT_ACTIVE);
        assertThat(MandateService.check(mandate("active", NOW.plusSeconds(10), "100", null, null), "shopbot", Money.of("10", "EUR"), null, NOW).reasons()).containsExactly(MandateService.REASON_CURRENCY);
        assertThat(MandateService.check(mandate("active", NOW.plusSeconds(10), "100", null, null), "shopbot", Money.usd("100.00"), "anyone", NOW).valid()).as("buyer ref optional when mandate has none; limit inclusive").isTrue();
        assertThat(MandateService.check(mandate("active", NOW.plusSeconds(10), "100", "b", null), "shopbot", Money.usd("1"), null, NOW).valid()).as("missing buyer ref on the order is fine").isTrue();
    }

    @Test
    void effectiveStatusReflectsExpiry() {
        Mandate m = mandate("active", NOW.minusSeconds(1), "10", null, null);
        assertThat(m.effectiveStatus(NOW)).isEqualTo(Mandate.EXPIRED);
        assertThat(m.effectiveStatus(NOW.minusSeconds(10))).isEqualTo(Mandate.ACTIVE);
    }

    @Test
    void hmacSignatureRoundTrip() {
        MandateScope scope = new MandateScope("demo", List.of("apparel"), 3);
        String canonical = MandateSigner.canonicalJson("buyer-1", scope, Money.usd("100"), "Buy two tees", NOW.plusSeconds(3600));
        assertThat(canonical).startsWith("{\"buyerRef\":\"buyer-1\"").contains("\"spendLimit\":{\"amount\":\"100.00\",\"currency\":\"USD\"}");
        String sig = MandateSigner.hmacSign("ags_secret", canonical);
        assertThat(MandateSigner.hmacVerify("ags_secret", canonical, sig)).isTrue();
        assertThat(MandateSigner.hmacVerify("ags_secret", canonical, "sha256=" + sig)).isTrue();
        assertThat(MandateSigner.hmacVerify("ags_secret", canonical.replace("100.00", "900.00"), sig)).isFalse();
        assertThat(MandateSigner.hmacVerify("wrong", canonical, sig)).isFalse();
    }

    @Test
    void ed25519SignatureVerifies() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String canonical = MandateSigner.canonicalJson("b", MandateScope.empty(), Money.usd("5"), "x", NOW);
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(kp.getPrivate());
        s.update(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String sig = Base64.getEncoder().encodeToString(s.sign());
        String pem = "-----BEGIN PUBLIC KEY-----\n" + Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----";
        assertThat(MandateSigner.publicKeyVerify(MandateSigner.parsePublicKey(pem), canonical, sig)).isTrue();
        assertThat(MandateSigner.publicKeyVerify(MandateSigner.parsePublicKey(pem), canonical + " ", sig)).isFalse();
    }

    @Test
    void scopeParsesObjectAndLabelForms() {
        MandateScope a = MandateScope.parse(java.util.Map.of("merchant", "demo", "categories", List.of("apparel", "shoes"), "maxItems", 4));
        assertThat(a.labels()).containsExactly("merchant:demo", "category:apparel", "category:shoes", "max_items:4");
        MandateScope b = MandateScope.parse(List.of("merchant:demo", "category:apparel", "max_items:2"));
        assertThat(b.merchant()).isEqualTo("demo");
        assertThat(b.categories()).containsExactly("apparel");
        assertThat(b.maxItems()).isEqualTo(2);
        assertThat(MandateScope.parse(null).labels()).isEmpty();
    }
}
