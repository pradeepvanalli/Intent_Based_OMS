package ai.intentoms.integrations.notify;

import ai.intentoms.integrations.config.IntegrationsConfig;
import ai.intentoms.integrations.config.IntegrationsProperties;
import ai.intentoms.platform.observability.Metrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SendGrid v3 Mail Send adapter (section 11 Notifications P0): {@code POST {baseUrl}/v3/mail/send} with
 * {@code Authorization: Bearer <api-key>} and body {@code {personalizations:[{to:[{email}]}], from:{email,name}, subject,
 * content:[{type:"text/plain", value}]}}; a 202 with {@code X-Message-Id} is a send. Only called when
 * {@code oms.integrations.notify.sendgrid.api-key} is present ({@link #configured()}).
 */
@Component
public class SendGridEmailSender {
    private static final Logger log = LoggerFactory.getLogger(SendGridEmailSender.class);
    public static final String PROVIDER = "sendgrid";
    private final IntegrationsProperties.SendGrid props;
    private final RestClient client;
    private final Metrics metrics;

    @Autowired
    public SendGridEmailSender(IntegrationsProperties props, RestClient.Builder builder, Metrics metrics) {
        this(props.notifications().sendgrid(), props.notifications().timeout(), builder, metrics);
    }

    SendGridEmailSender(IntegrationsProperties.SendGrid props, java.time.Duration timeout, RestClient.Builder builder, Metrics metrics) {
        this.props = props;
        this.client = IntegrationsConfig.client(builder, props.baseUrl(), timeout);
        this.metrics = metrics;
    }

    public boolean configured() { return props.configured(); }

    public ProviderResult send(String toEmail, String subject, String body) {
        if (!configured()) return ProviderResult.failed("sendgrid not configured");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("personalizations", List.of(Map.of("to", List.of(Map.of("email", toEmail)))));
        payload.put("from", Map.of("email", props.fromEmail(), "name", props.fromName()));
        payload.put("subject", subject == null || subject.isBlank() ? "(no subject)" : subject);
        payload.put("content", List.of(Map.of("type", "text/plain", "value", body == null ? "" : body)));
        try {
            ResponseEntity<Void> res = client.post().uri("/v3/mail/send").header(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey())
                .contentType(MediaType.APPLICATION_JSON).body(payload).retrieve().toBodilessEntity();
            String ref = res.getHeaders().getFirst("X-Message-Id");
            metrics.count("oms.integrations.notify.sent", "provider", PROVIDER);
            return ProviderResult.sent(ref == null ? "sendgrid:" + res.getStatusCode().value() : ref);
        } catch (RestClientResponseException e) {
            metrics.count("oms.integrations.notify.failed", "provider", PROVIDER);
            log.warn("SendGrid rejected message: {} {}", e.getStatusCode().value(), abbreviate(e.getResponseBodyAsString()));
            return ProviderResult.failed("HTTP " + e.getStatusCode().value() + ": " + abbreviate(e.getResponseBodyAsString()));
        } catch (RuntimeException e) {
            metrics.count("oms.integrations.notify.failed", "provider", PROVIDER);
            log.warn("SendGrid call failed: {}", e.getMessage());
            return ProviderResult.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    static String abbreviate(String s) { return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "…" : s; }
}
