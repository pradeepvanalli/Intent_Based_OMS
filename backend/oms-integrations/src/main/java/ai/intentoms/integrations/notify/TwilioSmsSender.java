package ai.intentoms.integrations.notify;

import ai.intentoms.integrations.config.IntegrationsConfig;
import ai.intentoms.integrations.config.IntegrationsProperties;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.observability.Metrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

/**
 * Twilio Programmable Messaging adapter (section 11 Notifications P0): {@code POST {baseUrl}/2010-04-01/Accounts/{sid}/Messages.json}
 * form-encoded {@code To, From, Body} with HTTP basic auth {@code accountSid:authToken}; a 201 with {@code sid} is a send.
 * SMS bodies are the template body only (no subject), truncated to 1600 characters (Twilio's concatenated limit).
 */
@Component
public class TwilioSmsSender {
    private static final Logger log = LoggerFactory.getLogger(TwilioSmsSender.class);
    public static final String PROVIDER = "twilio";
    private final IntegrationsProperties.Twilio props;
    private final RestClient client;
    private final Metrics metrics;

    @Autowired
    public TwilioSmsSender(IntegrationsProperties props, RestClient.Builder builder, Metrics metrics) {
        this(props.notifications().twilio(), props.notifications().timeout(), builder, metrics);
    }

    TwilioSmsSender(IntegrationsProperties.Twilio props, java.time.Duration timeout, RestClient.Builder builder, Metrics metrics) {
        this.props = props;
        this.client = IntegrationsConfig.client(builder, props.baseUrl(), timeout);
        this.metrics = metrics;
    }

    public boolean configured() { return props.configured(); }

    public ProviderResult send(String toPhone, String body) {
        if (!configured()) return ProviderResult.failed("twilio not configured");
        String text = body == null ? "" : body.length() > 1600 ? body.substring(0, 1600) : body;
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("To", toPhone);
        form.add("From", props.from());
        form.add("Body", text);
        try {
            String res = client.post().uri("/2010-04-01/Accounts/{sid}/Messages.json", props.accountSid())
                .headers(h -> h.setBasicAuth(props.accountSid(), props.authToken()))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(String.class);
            Map<String, Object> json = res == null ? Map.of() : Json.readMap(res);
            metrics.count("oms.integrations.notify.sent", "provider", PROVIDER);
            return ProviderResult.sent(json.get("sid") == null ? "twilio:accepted" : String.valueOf(json.get("sid")));
        } catch (RestClientResponseException e) {
            metrics.count("oms.integrations.notify.failed", "provider", PROVIDER);
            log.warn("Twilio rejected message: {} {}", e.getStatusCode().value(), SendGridEmailSender.abbreviate(e.getResponseBodyAsString()));
            return ProviderResult.failed("HTTP " + e.getStatusCode().value() + ": " + SendGridEmailSender.abbreviate(e.getResponseBodyAsString()));
        } catch (RuntimeException e) {
            metrics.count("oms.integrations.notify.failed", "provider", PROVIDER);
            log.warn("Twilio call failed: {}", e.getMessage());
            return ProviderResult.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
