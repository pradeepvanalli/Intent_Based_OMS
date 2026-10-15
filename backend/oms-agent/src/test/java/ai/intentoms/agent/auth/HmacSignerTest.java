package ai.intentoms.agent.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-AGT-03 / GR-14: request signing and the replay window. */
class HmacSignerTest {
    private static final String SECRET = "ags_test_secret";

    @Test
    void signsAndVerifiesCanonicalString() {
        byte[] body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}".getBytes(StandardCharsets.UTF_8);
        String bodyHash = HmacSigner.sha256Hex(body);
        String sig = HmacSigner.sign(SECRET, "1700000000", "post", "/mcp", body);
        assertThat(sig).matches("[0-9a-f]{64}");
        assertThat(HmacSigner.stringToSign("1700000000", "post", "/mcp", bodyHash)).isEqualTo("1700000000\nPOST\n/mcp\n" + bodyHash);
        assertThat(HmacSigner.verify(SECRET, "1700000000", "POST", "/mcp", bodyHash, sig)).isTrue();
        assertThat(HmacSigner.verify(SECRET, "1700000000", "POST", "/mcp", bodyHash, "sha256=" + sig.toUpperCase())).isTrue();
    }

    @Test
    void rejectsTamperedBodyPathMethodOrSecret() {
        byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        String sig = HmacSigner.sign(SECRET, "1700000000", "POST", "/mcp", body);
        assertThat(HmacSigner.verify(SECRET, "1700000000", "POST", "/mcp", HmacSigner.sha256Hex("{\"a\":2}"), sig)).isFalse();
        assertThat(HmacSigner.verify(SECRET, "1700000000", "POST", "/ucp/v1/orders", HmacSigner.sha256Hex(body), sig)).isFalse();
        assertThat(HmacSigner.verify(SECRET, "1700000000", "GET", "/mcp", HmacSigner.sha256Hex(body), sig)).isFalse();
        assertThat(HmacSigner.verify("other", "1700000000", "POST", "/mcp", HmacSigner.sha256Hex(body), sig)).isFalse();
        assertThat(HmacSigner.verify(SECRET, "1700000001", "POST", "/mcp", HmacSigner.sha256Hex(body), sig)).isFalse();
        assertThat(HmacSigner.verify(SECRET, "1700000000", "POST", "/mcp", HmacSigner.sha256Hex(body), null)).isFalse();
    }

    @Test
    void timestampWindowAcceptsEpochSecondsMillisAndIso() {
        Instant now = Instant.parse("2026-10-05T10:00:00Z");
        Duration window = Duration.ofSeconds(300);
        assertThat(HmacSigner.withinWindow(String.valueOf(now.getEpochSecond()), now, window)).isTrue();
        assertThat(HmacSigner.withinWindow(String.valueOf(now.toEpochMilli() - 1000), now, window)).isTrue();
        assertThat(HmacSigner.withinWindow(now.minusSeconds(299).toString(), now, window)).isTrue();
        assertThat(HmacSigner.withinWindow(String.valueOf(now.getEpochSecond() - 301), now, window)).isFalse();
        assertThat(HmacSigner.withinWindow(String.valueOf(now.getEpochSecond() + 301), now, window)).isFalse();
        assertThat(HmacSigner.withinWindow("not-a-time", now, window)).isFalse();
        assertThat(HmacSigner.withinWindow(null, now, window)).isFalse();
    }

    @Test
    void emptyBodyHashesToSha256OfNothing() {
        assertThat(HmacSigner.sha256Hex((byte[]) null)).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }
}
