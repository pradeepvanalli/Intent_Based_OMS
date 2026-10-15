package ai.intentoms.agent.ucp;

import ai.intentoms.agent.auth.HmacSigner;
import ai.intentoms.agent.config.AgentProperties;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.util.Map;

/**
 * Delivers UCP lifecycle webhooks (FR-AGT-02) to an agent's callback URL, signed with the agent's HMAC secret
 * ({@code X-UCP-Signature: sha256=<hex>}), and logs every attempt in {@code agent_webhook_delivery}.
 */
@Component
public class UcpWebhookSender {
    public static final String SIGNATURE_HEADER = "X-UCP-Signature";
    private static final Logger log = LoggerFactory.getLogger(UcpWebhookSender.class);
    private final RestClient http;
    private final JdbcClient jdbc;
    private final Metrics metrics;

    public UcpWebhookSender(JdbcClient jdbc, Metrics metrics, AgentProperties props) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(props.webhookTimeout()).build());
        factory.setReadTimeout(props.webhookTimeout());
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    public record Delivery(boolean delivered, Integer httpStatus, String error) {}

    public Delivery send(String agentId, String secret, String url, String eventId, String type, String orderId, Map<String, Object> payload) {
        String body = Json.write(payload);
        String sig = "sha256=" + HmacSigner.hmacHex(secret, body);
        Integer status = null;
        String error = null;
        try {
            var resp = http.post().uri(url)
                .header("Content-Type", "application/json")
                .header(SIGNATURE_HEADER, sig)
                .header("X-UCP-Event-Id", eventId)
                .header("X-UCP-Event-Type", type)
                .header(UcpVersions.HEADER, UcpVersions.DEFAULT)
                .body(body).retrieve().toBodilessEntity();
            status = resp.getStatusCode().value();
        } catch (RestClientResponseException e) {
            status = e.getStatusCode().value();
            error = "HTTP " + status;
        } catch (Exception e) {
            error = e.toString();
        }
        boolean ok = error == null && status != null && status >= 200 && status < 300;
        jdbc.sql("insert into agent_webhook_delivery(tenant_id, agent_id, order_id, event_id, type, url, status, http_status, error, delivered_at) values (:t, :a, :o, :e, :type, :url, :s, :http, :err, case when :ok then now() else null end)")
            .param("t", TenantContext.require()).param("a", agentId).param("o", orderId).param("e", eventId).param("type", type).param("url", url)
            .param("s", ok ? "delivered" : "failed").param("http", status).param("err", error).param("ok", ok).update();
        metrics.count("oms.agent.webhook", "type", type, "outcome", ok ? "delivered" : "failed");
        if (!ok) log.warn("UCP webhook {} to {} for agent {} failed: {}", type, url, agentId, error == null ? "HTTP " + status : error);
        return new Delivery(ok, status, error);
    }

    public boolean alreadyDelivered(String eventId, String agentId) {
        Integer n = jdbc.sql("select count(*) from agent_webhook_delivery where tenant_id = :t and event_id = :e and agent_id = :a and status = 'delivered'")
            .param("t", TenantContext.require()).param("e", eventId).param("a", agentId).query(Integer.class).single();
        return n != null && n > 0;
    }

}
