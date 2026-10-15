package ai.intentoms.order.tracking;

import ai.intentoms.platform.security.SecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TrackingTokenServiceTest {
    private static SecurityProperties withSecret(String secret) {
        MapConfigurationPropertySource src = new MapConfigurationPropertySource(Map.of("oms.security.jwt-secret", secret));
        return new Binder(src).bind("oms.security", SecurityProperties.class).get();
    }

    @Test
    void roundTripsAndIsUrlSafe() {
        TrackingTokenService svc = new TrackingTokenService(withSecret("unit-test-secret-which-is-long-enough-123"));
        Instant now = Instant.parse("2026-10-08T10:00:00Z");
        TrackingTokenService.Issued issued = svc.issue("demo", "ord_abc123", now);
        assertThat(issued.token()).matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
        assertThat(issued.expiresAt()).isEqualTo(now.plus(Duration.ofDays(30)));
        Optional<TrackingTokenService.Claims> claims = svc.verify(issued.token(), now.plus(Duration.ofDays(29)));
        assertThat(claims).isPresent();
        assertThat(claims.get().tenantId()).isEqualTo("demo");
        assertThat(claims.get().orderId()).isEqualTo("ord_abc123");
        assertThat(claims.get().expiresAt()).isEqualTo(issued.expiresAt());
    }

    @Test
    void rejectsExpiredTamperedForeignAndMalformedTokens() {
        TrackingTokenService svc = new TrackingTokenService(withSecret("unit-test-secret-which-is-long-enough-123"));
        TrackingTokenService other = new TrackingTokenService(withSecret("another-secret-of-a-different-deployment"));
        Instant now = Instant.parse("2026-10-08T10:00:00Z");
        String token = svc.issue("demo", "ord_abc123", now).token();
        assertThat(svc.verify(token, now.plus(Duration.ofDays(31)))).isEmpty();
        String payload = token.substring(0, token.indexOf('.'));
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(("other|ord_abc123|" + now.plus(Duration.ofDays(30)).getEpochSecond()).getBytes());
        assertThat(svc.verify(forgedPayload + token.substring(token.indexOf('.')), now)).as("tenant swapped").isEmpty();
        assertThat(svc.verify(payload + ".AAAA", now)).as("bad signature").isEmpty();
        assertThat(other.verify(token, now)).as("different secret").isEmpty();
        assertThat(svc.verify("", now)).isEmpty();
        assertThat(svc.verify("not-a-token", now)).isEmpty();
        assertThat(svc.verify("a.b", now)).isEmpty();
        assertThat(svc.verify(null, now)).isEmpty();
    }
}
