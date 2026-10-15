package ai.intentoms.platform.ledger;

import ai.intentoms.platform.json.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Delivers CloudEvents to tenant webhook subscriptions (NFR-11). Each delivery is HMAC-SHA256 signed with the
 * subscription secret (header X-OMS-Signature). Failures are logged to webhook_delivery and retried by the outbox.
 */
@Component
public class WebhookPublisher implements EventPublisher {
    private static final Logger log = LoggerFactory.getLogger(WebhookPublisher.class);
    private final JdbcClient jdbc;
    private final RestClient http = RestClient.builder().requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory()).build();

    public WebhookPublisher(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public void publish(Event e) {
        List<Map<String, Object>> subs = jdbc.sql("select id, url, secret, event_filter::text as filter from webhook_subscription where tenant_id = :t and active")
            .param("t", e.tenantId()).query().listOfRows();
        for (Map<String, Object> s : subs) {
            List<String> filter = Json.read((String) s.get("filter"), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
            if (!OutboxRelay.matches(filter, e.type())) continue;
            String body = Json.write(e.toCloudEvent());
            String sig = sign((String) s.get("secret"), body);
            try {
                http.post().uri((String) s.get("url"))
                    .header("Content-Type", "application/cloudevents+json")
                    .header("X-OMS-Signature", "sha256=" + sig)
                    .header("X-OMS-Event-Id", e.id())
                    .body(body).retrieve().toBodilessEntity();
                jdbc.sql("insert into webhook_delivery(subscription_id, event_id, status, delivered_at) values (:s, :e, 'delivered', now())")
                    .param("s", s.get("id")).param("e", e.id()).update();
            } catch (Exception ex) {
                jdbc.sql("insert into webhook_delivery(subscription_id, event_id, status, error) values (:s, :e, 'failed', :err)")
                    .param("s", s.get("id")).param("e", e.id()).param("err", ex.toString()).update();
                log.warn("Webhook {} failed for event {}: {}", s.get("url"), e.id(), ex.toString());
            }
        }
    }

    public static String sign(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
