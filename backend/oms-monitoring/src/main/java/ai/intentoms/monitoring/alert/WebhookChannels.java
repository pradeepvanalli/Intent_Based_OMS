package ai.intentoms.monitoring.alert;

import ai.intentoms.platform.config.ConfigKeys;
import ai.intentoms.platform.config.TenantConfigStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Outbound alert channels (FR-MON-06): Slack incoming webhook ({@code alerts.slack_webhook}), Microsoft Teams webhook
 * ({@code alerts.teams_webhook}, MessageCard), e-mail ({@code alerts.email}) through {@link EmailSender}, and PagerDuty
 * Events v2 ({@code alerts.pagerduty_routing_key}, severity critical only). Webhook calls time out after 5 s.
 */
@Configuration
public class WebhookChannels {
    public static final String TEAMS_WEBHOOK_KEY = "alerts.teams_webhook";
    public static final String PAGERDUTY_URL = "https://events.pagerduty.com/v2/enqueue";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Bean
    @ConditionalOnMissingBean(EmailSender.class)
    EmailSender loggingEmailSender() { return new LoggingEmailSender(); }

    @Bean
    AlertChannel slackAlertChannel(RestClient.Builder builder) {
        RestClient client = client(builder);
        return new AlertChannel() {
            @Override public String name() { return "slack"; }
            @Override public boolean configured(TenantConfigStore c, Alert a) { return present(c, ConfigKeys.ALERT_SLACK_WEBHOOK); }
            @Override public void send(TenantConfigStore c, Alert a) {
                String text = "*" + a.title() + "*\n" + a.body() + links(a, "<", "|", ">");
                client.post().uri(c.getString(ConfigKeys.ALERT_SLACK_WEBHOOK, "")).contentType(MediaType.APPLICATION_JSON).body(Map.of("text", text)).retrieve().toBodilessEntity();
            }
        };
    }

    @Bean
    AlertChannel teamsAlertChannel(RestClient.Builder builder) {
        RestClient client = client(builder);
        return new AlertChannel() {
            @Override public String name() { return "teams"; }
            @Override public boolean configured(TenantConfigStore c, Alert a) { return present(c, TEAMS_WEBHOOK_KEY); }
            @Override public void send(TenantConfigStore c, Alert a) {
                Map<String, Object> card = new LinkedHashMap<>();
                card.put("@type", "MessageCard");
                card.put("@context", "https://schema.org/extensions");
                card.put("themeColor", a.critical() ? "D7263D" : "high".equals(a.severity()) ? "F46036" : "2E86AB");
                card.put("summary", a.title());
                card.put("title", a.title());
                card.put("text", a.body());
                card.put("potentialAction", a.links().entrySet().stream().map(e -> Map.of("@type", "OpenUri", "name", e.getKey(), "targets", List.of(Map.of("os", "default", "uri", e.getValue())))).toList());
                client.post().uri(c.getString(TEAMS_WEBHOOK_KEY, "")).contentType(MediaType.APPLICATION_JSON).body(card).retrieve().toBodilessEntity();
            }
        };
    }

    @Bean
    AlertChannel emailAlertChannel(EmailSender sender) {
        return new AlertChannel() {
            @Override public String name() { return "email"; }
            @Override public boolean configured(TenantConfigStore c, Alert a) { return present(c, ConfigKeys.ALERT_EMAIL); }
            @Override public void send(TenantConfigStore c, Alert a) { sender.send(c.getString(ConfigKeys.ALERT_EMAIL, ""), a.title(), a.body() + links(a, "", ": ", "")); }
        };
    }

    @Bean
    AlertChannel pagerDutyAlertChannel(RestClient.Builder builder) {
        RestClient client = client(builder);
        return new AlertChannel() {
            @Override public String name() { return "pagerduty"; }
            @Override public boolean configured(TenantConfigStore c, Alert a) { return a.critical() && present(c, ConfigKeys.ALERT_PAGERDUTY_KEY); }
            @Override public void send(TenantConfigStore c, Alert a) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("summary", a.title());
                payload.put("source", "intent-oms");
                payload.put("severity", "critical");
                payload.put("custom_details", Map.of("body", a.body(), "links", a.links()));
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("routing_key", c.getString(ConfigKeys.ALERT_PAGERDUTY_KEY, ""));
                event.put("event_action", "trigger");
                event.put("dedup_key", a.key());
                event.put("payload", payload);
                client.post().uri(PAGERDUTY_URL).contentType(MediaType.APPLICATION_JSON).body(event).retrieve().toBodilessEntity();
            }
        };
    }

    private static RestClient client(RestClient.Builder builder) {
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory();
        factory.setReadTimeout(TIMEOUT);
        return builder.clone().requestFactory(factory).build();
    }

    private static boolean present(TenantConfigStore c, String key) {
        String v = c.getString(key, "");
        return v != null && !v.isBlank() && !"null".equals(v);
    }

    private static String links(Alert a, String open, String sep, String close) {
        if (a.links().isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n");
        a.links().forEach((k, v) -> sb.append(open).append(v).append(sep).append(k).append(close).append(' '));
        return sb.toString();
    }
}
