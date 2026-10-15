package ai.intentoms.agent.ucp;

import ai.intentoms.agent.AgentTestBase;
import ai.intentoms.agent.CustomerSafeOrderView;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.Event;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/** FR-AGT-02: UCP order capability over HTTP plus lifecycle webhook delivery. */
class UcpIntegrationTest extends AgentTestBase {
    static WireMockServer wiremock;
    @Autowired UcpWebhookSubscriber subscriber;

    @BeforeAll static void startWireMock() { wiremock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort()); wiremock.start(); }
    @AfterAll static void stopWireMock() { wiremock.stop(); }

    private Map<String, Object> ucpOrder(String clientRef, String buyerRef, Map<String, Object> mandate) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mandate", mandate);
        body.put("buyer", Map.of("ref", buyerRef, "email", "jane@example.com", "name", "Jane Doe"));
        body.put("line_items", List.of(Map.of("sku", "TEE-BLK-M", "quantity", 2, "unit_price", Map.of("amount", 28.00, "currency", "USD"))));
        body.put("ship_to", Map.of("name", "Jane Doe", "line1", "1 Market St", "city", "San Francisco", "state", "CA", "postal_code", "94105", "country", "US"));
        body.put("payment", Map.of("token", "tok_agent_" + Ids.shortId(), "type", "agent_token"));
        body.put("instructions", "Two black tees in medium, ship together");
        body.put("client_order_id", clientRef);
        body.put("charges", List.of(Map.of("type", "shipping", "amount", Map.of("amount", 6.00, "currency", "USD"))));
        return body;
    }

    @Test
    void discoveryIsPublic() {
        ResponseEntity<String> r = http.get().uri("http://localhost:" + port + "/.well-known/ucp").retrieve().toEntity(String.class);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        JsonNode d = Json.tree(r.getBody());
        assertThat(d.path("ucp_versions").get(0).asText()).isEqualTo("2026-08-25");
        assertThat(d.path("capabilities").path("order").path("endpoints").path("place_order").asText()).isEqualTo("POST /ucp/v1/orders");
    }

    @Test
    void placeGetEventsAndVersionNegotiation() {
        TestAgent bot = shopper();
        String ref = "UCP-" + Ids.shortId();
        Map<String, Object> inlineMandate = Map.of("buyer_ref", "buyer-ucp", "scope", List.of("merchant:demo", "category:apparel"), "spend_limit", Map.of("amount", 150.00, "currency", "USD"),
            "expires_at", Instant.now().plusSeconds(3600).toString());
        ResponseEntity<String> placed = signed(bot.id(), bot.secret(), signedHeaderTimestamp(), HttpMethod.POST, "/ucp/v1/orders", ucpOrder(ref, "buyer-ucp", inlineMandate), Map.of("UCP-Version", "2026-08-25"));
        assertThat(placed.getStatusCode().value()).as(placed.getBody()).isEqualTo(201);
        assertThat(placed.getHeaders().getFirst("UCP-Version")).isEqualTo("2026-08-25");
        JsonNode p = Json.tree(placed.getBody());
        assertThat(p.path("ucp_version").asText()).isEqualTo("2026-08-25");
        String orderId = p.path("order_id").asText();
        assertThat(p.path("mandate_id").asText()).startsWith("mnd_");
        assertThat(p.path("order").path("line_items").get(0).path("fulfillment").path("status").asText()).isEqualTo("created");

        ResponseEntity<String> got = signed(bot, HttpMethod.GET, "/ucp/v1/orders/" + orderId, null);
        assertThat(got.getStatusCode().value()).isEqualTo(200);
        JsonNode g = Json.tree(got.getBody());
        assertThat(g.path("order_id").asText()).isEqualTo(orderId);
        assertThat(g.path("client_order_id").asText()).isEqualTo(ref);
        assertThat(g.has("tracking")).isTrue();
        assertThat(g.has("returns")).isTrue();
        assertThat(CustomerSafeOrderView.isCustomerSafe(Json.toMap(g))).isTrue();
        assertThat(g.path("agent").path("protocol").asText()).isEqualTo("ucp");

        ResponseEntity<String> events = signed(bot, HttpMethod.GET, "/ucp/v1/orders/" + orderId + "/events", null);
        JsonNode ev = Json.tree(events.getBody());
        assertThat(ev.path("events").findValuesAsText("type")).contains("order.captured");
        assertThat(CustomerSafeOrderView.isCustomerSafe(Json.toMap(ev))).isTrue();

        ResponseEntity<String> badVersion = signed(bot.id(), bot.secret(), signedHeaderTimestamp(), HttpMethod.GET, "/ucp/v1/orders/" + orderId, null, Map.of("UCP-Version", "2024-01-01"));
        assertThat(badVersion.getStatusCode().value()).isEqualTo(400);
        assertThat(badVersion.getBody()).contains("supported_versions").contains("2026-08-25");

        TestAgent stranger = registerAgent(0, List.of("order_get"), null, 100, List.of(), null);
        assertThat(signed(stranger, HttpMethod.GET, "/ucp/v1/orders/" + orderId, null).getStatusCode().value()).isEqualTo(403);
        assertThat(http.get().uri("http://localhost:" + port + "/ucp/v1/orders/" + orderId).retrieve().toEntity(String.class).getStatusCode().value()).isEqualTo(401);

        ResponseEntity<String> overLimit = signed(bot, HttpMethod.POST, "/ucp/v1/orders", ucpOrder("UCP-" + Ids.shortId(), "buyer-ucp",
            Map.of("buyer_ref", "buyer-ucp", "spend_limit", Map.of("amount", 10.00, "currency", "USD"))));
        assertThat(overLimit.getStatusCode().value()).isEqualTo(422);
        assertThat(overLimit.getBody()).contains("mandate_invalid");
    }

    @Test
    void lifecycleWebhookIsSignedAndLogged() {
        wiremock.resetAll();
        wiremock.stubFor(post(urlEqualTo("/hooks/ucp")).willReturn(aResponse().withStatus(200)));
        TestAgent bot = registerAgent(2, List.of("order_get", "order_create"), new BigDecimal("1500"), 600, List.of(), "http://localhost:" + wiremock.port() + "/hooks/ucp");
        var m = mandate(bot, "buyer-wh", "200.00", "Buy two tees");
        ResponseEntity<String> placed = signed(bot, HttpMethod.POST, "/ucp/v1/orders", ucpOrder("UCP-" + Ids.shortId(), "buyer-wh", Map.of("id", m.id())));
        assertThat(placed.getStatusCode().value()).as(placed.getBody()).isEqualTo(201);
        String orderId = Json.tree(placed.getBody()).path("order_id").asText();

        Event shipped = new Event("evt-" + Ids.shortId(), TENANT, "order", orderId, 9, "order.transitioned",
            Map.of("entityType", "order", "entityId", orderId, "from", "released", "to", "shipped", "trigger", "rollup"), Actor.system("test"), null, orderId, Map.of(), Instant.now());
        subscriber.on(shipped);
        subscriber.on(shipped);
        subscriber.on(new Event("evt-" + Ids.shortId(), TENANT, "order", orderId, 10, "order.transitioned", Map.of("entityType", "order_line", "to", "shipped"), Actor.system("test"), null, orderId, Map.of(), Instant.now()));

        var requests = wiremock.findAll(postRequestedFor(urlEqualTo("/hooks/ucp")));
        assertThat(requests).as("delivered once, idempotent on retry, line-level ignored").hasSize(1);
        var req = requests.get(0);
        JsonNode body = Json.tree(req.getBodyAsString());
        assertThat(body.path("type").asText()).isEqualTo("order.shipped");
        assertThat(body.path("order_id").asText()).isEqualTo(orderId);
        assertThat(body.path("agent_id").asText()).isEqualTo(bot.id());
        assertThat(body.path("ucp_version").asText()).isEqualTo("2026-08-25");
        String sig = req.getHeader("X-UCP-Signature");
        assertThat(sig).isEqualTo("sha256=" + ai.intentoms.agent.auth.HmacSigner.hmacHex(bot.secret(), req.getBodyAsString()));
        List<Map<String, Object>> rows = jdbc.sql("select type, status, http_status from agent_webhook_delivery where tenant_id = :t and agent_id = :a").param("t", TENANT).param("a", bot.id()).query().listOfRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("status", "delivered").containsEntry("type", "order.shipped");
    }
}
