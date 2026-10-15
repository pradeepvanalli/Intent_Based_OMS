package ai.intentoms.integrations.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which adapters are active and whether their credentials are present, for {@code GET /api/v1/integrations/status}.
 * Only booleans and non-secret endpoints are exposed; secrets never leave the process (NFR-08).
 */
public final class IntegrationsStatus {
    private final IntegrationsProperties props;

    IntegrationsStatus(IntegrationsProperties props) { this.props = props; }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("graphql", Map.of("active", true, "endpoint", "/graphql", "graphiql", props.graphiql()));
        out.put("marketplace", Map.of(
            "webhook", Map.of("active", true, "endpoint", "/webhooks/marketplace/{marketplace}", "shapes", java.util.List.of("amazon", "generic")),
            "amazonPoller", Map.of("active", props.marketplace().amazon().enabled(), "configured", props.marketplace().amazon().credentialsPresent(),
                "baseUrl", props.marketplace().amazon().baseUrl(), "marketplaceIds", props.marketplace().amazon().marketplaceIds(), "tenant", props.marketplace().amazon().tenant())));
        out.put("notify", Map.of(
            "active", props.notifications().enabled(),
            "sendgrid", Map.of("configured", props.notifications().sendgrid().configured(), "baseUrl", props.notifications().sendgrid().baseUrl(), "from", props.notifications().sendgrid().fromEmail()),
            "twilio", Map.of("configured", props.notifications().twilio().configured(), "baseUrl", props.notifications().twilio().baseUrl())));
        out.put("address", Map.of("provider", props.address().provider(), "active", "smarty".equalsIgnoreCase(props.address().provider()),
            "configured", props.address().smarty().configured(), "baseUrl", props.address().smarty().baseUrl(), "timeout", props.address().smarty().timeout().toString()));
        out.put("carrier", Map.of("provider", props.carrier().provider(), "active", "easypost".equalsIgnoreCase(props.carrier().provider()),
            "configured", props.carrier().easypost().configured(), "baseUrl", props.carrier().easypost().baseUrl(), "webhook", "/webhooks/easypost"));
        out.put("wms", Map.of("provider", props.wms().provider(), "active", "http".equalsIgnoreCase(props.wms().provider()),
            "retries", props.wms().http().retries(), "timeout", props.wms().http().timeout().toString(), "callback", "/webhooks/wms/{nodeId}"));
        return out;
    }
}
