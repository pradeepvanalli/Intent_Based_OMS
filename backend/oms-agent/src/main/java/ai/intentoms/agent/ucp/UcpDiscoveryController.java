package ai.intentoms.agent.ucp;

import ai.intentoms.agent.auth.HmacSigner;
import ai.intentoms.agent.mcp.McpServer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** UCP capability discovery (FR-AGT-02) at the public path {@code /.well-known/ucp}: versions, endpoints, auth schemes and webhook events. */
@RestController
public class UcpDiscoveryController {

    @GetMapping("/.well-known/ucp")
    public Map<String, Object> discovery() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ucp_versions", UcpVersions.SUPPORTED);
        out.put("default_version", UcpVersions.DEFAULT);
        out.put("version_header", UcpVersions.HEADER);
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("version", UcpVersions.DEFAULT);
        order.put("endpoints", Map.of(
            "get_order", "GET /ucp/v1/orders/{id}",
            "place_order", "POST /ucp/v1/orders",
            "create_return", "POST /ucp/v1/orders/{id}/returns",
            "order_events", "GET /ucp/v1/orders/{id}/events"));
        order.put("lifecycle_webhooks", Map.of("events", List.of("order.shipped", "order.delivered", "return.created", "refund.issued"),
            "signature_header", UcpWebhookSender.SIGNATURE_HEADER, "signature", "sha256=hex(HMAC-SHA256(agent_secret, body))", "delivery_to", "agent callback_url"));
        out.put("capabilities", Map.of("order", order, "mcp", Map.of("endpoint", "POST /mcp", "protocol_version", McpServer.PROTOCOL_VERSION)));
        out.put("authentication", List.of(
            Map.of("scheme", "hmac-sha256", "headers", List.of(HmacSigner.HEADER_AGENT, HmacSigner.HEADER_TIMESTAMP, HmacSigner.HEADER_SIGNATURE),
                "string_to_sign", "timestamp\\nMETHOD\\npath\\nsha256hex(body)", "window_seconds", 300),
            Map.of("scheme", "api_key", "header", "X-Api-Key")));
        out.put("mandates", Map.of("style", "AP2", "signed_fields", List.of("buyerRef", "scope", "spendLimit", "instructionText", "expiresAt"), "endpoint", "POST /api/v1/mandates"));
        return out;
    }
}
