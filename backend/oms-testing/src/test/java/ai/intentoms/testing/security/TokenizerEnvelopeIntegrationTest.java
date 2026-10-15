package ai.intentoms.testing.security;

import ai.intentoms.platform.pii.EnvelopeCipher;
import ai.intentoms.platform.pii.Tokenizer;
import ai.intentoms.platform.security.SecurityProperties;
import ai.intentoms.platform.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** NFR-08: tenant_key rows are envelope-encrypted when a master key is present, legacy plaintext rows stay readable and are upgraded in place. */
class TokenizerEnvelopeIntegrationTest extends SecurityHardeningTestBase {
    @Autowired Tokenizer plaintextTokenizer; // the context's bean: no master key configured in tests
    @Autowired SecurityProperties props;

    private static final String MASTER = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    private SecurityProperties withMasterKey(String key) {
        return new SecurityProperties(props.jwtSecret(), props.jwtSecretFile(), props.jwtTtl(), props.devUsers(), props.publicPaths(), props.enforceProduction(), props.hsts(),
            props.contentSecurityPolicy(), props.swaggerContentSecurityPolicy(), props.permissionsPolicy(), props.trustForwardedFor(), props.loginRateLimit(), props.loginRateWindow(),
            props.passwordMinLength(), key, props.cors(), props.oidc());
    }

    private String stored(String tenant) {
        return jdbc.sql("select key_material from tenant_key where tenant_id = :t and purpose = 'pii' and active").param("t", tenant).query(String.class).single();
    }

    @Test
    void legacyPlaintextKeyIsUpgradedInPlaceAndTokensStayStable() {
        String tenant = "env_" + System.nanoTime();
        jdbc.sql("insert into tenant(tenant_id, name) values (:t, :t) on conflict do nothing").param("t", tenant).update();
        assertThat(plaintextTokenizer.envelopeEnabled()).isFalse();
        String before = TenantContext.supplyAs(tenant, () -> plaintextTokenizer.email("Alice@Example.com"));
        assertThat(EnvelopeCipher.isEncrypted(stored(tenant))).isFalse();

        Tokenizer wrapped = new Tokenizer(jdbc, withMasterKey(MASTER));
        assertThat(wrapped.envelopeEnabled()).isTrue();
        String after = TenantContext.supplyAs(tenant, () -> wrapped.email("alice@example.com"));
        assertThat(after).isEqualTo(before);
        assertThat(EnvelopeCipher.isEncrypted(stored(tenant))).isTrue();

        // a fresh instance with the same master key decrypts the wrapped row
        Tokenizer again = new Tokenizer(jdbc, withMasterKey(MASTER));
        assertThat(TenantContext.supplyAs(tenant, () -> again.email("ALICE@example.com"))).isEqualTo(before);
        // a wrong master key or no master key cannot read it
        assertThatThrownBy(() -> TenantContext.supplyAs(tenant, () -> new Tokenizer(jdbc, withMasterKey("wrong-key")).email("x@y.z"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TenantContext.supplyAs(tenant, () -> new Tokenizer(jdbc, withMasterKey("")).email("x@y.z"))).hasMessageContaining("master-key is not set");
    }

    @Test
    void newTenantsGetEncryptedKeysDirectly() {
        String tenant = "envnew_" + System.nanoTime();
        jdbc.sql("insert into tenant(tenant_id, name) values (:t, :t) on conflict do nothing").param("t", tenant).update();
        Tokenizer wrapped = new Tokenizer(jdbc, withMasterKey("a passphrase works too"));
        String t1 = TenantContext.supplyAs(tenant, () -> wrapped.phone("+1 555 0100"));
        assertThat(t1).startsWith("ph_");
        assertThat(stored(tenant)).startsWith("enc:");
        wrapped.shred(tenant);
        assertThat(TenantContext.supplyAs(tenant, () -> wrapped.phone("+1 555 0100"))).isNotEqualTo(t1);
    }
}
