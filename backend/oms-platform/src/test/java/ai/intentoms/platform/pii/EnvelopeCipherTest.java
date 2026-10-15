package ai.intentoms.platform.pii;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvelopeCipherTest {
    private static final String KEY_B64 = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void roundTripsWithRandomIvAndPrefix() {
        EnvelopeCipher c = EnvelopeCipher.fromMasterKey(KEY_B64).orElseThrow();
        byte[] material = "0123456789abcdef0123456789abcdef".getBytes();
        String e1 = c.encrypt(material);
        String e2 = c.encrypt(material);
        assertThat(e1).startsWith("enc:").isNotEqualTo(e2);
        assertThat(EnvelopeCipher.isEncrypted(e1)).isTrue();
        assertThat(c.decrypt(e1)).isEqualTo(material);
        assertThat(c.decrypt(e2)).isEqualTo(material);
    }

    @Test
    void passphraseIsHashedAndWrongKeyFails() {
        EnvelopeCipher a = EnvelopeCipher.fromMasterKey("correct horse battery staple").orElseThrow();
        EnvelopeCipher b = EnvelopeCipher.fromMasterKey("wrong passphrase").orElseThrow();
        String enc = a.encrypt(new byte[]{1, 2, 3});
        assertThat(a.decrypt(enc)).containsExactly(1, 2, 3);
        assertThatThrownBy(() -> b.decrypt(enc)).isInstanceOf(IllegalStateException.class).hasMessageContaining("master-key");
        assertThat(EnvelopeCipher.deriveKey("correct horse battery staple")).hasSize(32);
        assertThat(EnvelopeCipher.deriveKey(KEY_B64)).isEqualTo(new byte[32]);
    }

    @Test
    void legacyPlaintextIsRecognisedAndEmptyKeyDisables() {
        assertThat(EnvelopeCipher.isEncrypted(Base64.getEncoder().encodeToString(new byte[32]))).isFalse();
        assertThat(EnvelopeCipher.isEncrypted(null)).isFalse();
        assertThat(EnvelopeCipher.fromMasterKey("")).isEmpty();
        assertThat(EnvelopeCipher.fromMasterKey(null)).isEmpty();
        assertThatThrownBy(() -> EnvelopeCipher.fromMasterKey(KEY_B64).orElseThrow().decrypt("plain")).isInstanceOf(IllegalArgumentException.class);
    }
}
