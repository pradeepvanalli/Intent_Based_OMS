package ai.intentoms.agent;

import ai.intentoms.agent.auth.HmacSigner;
import ai.intentoms.agent.mandate.Mandate;
import ai.intentoms.agent.mandate.MandateCreate;
import ai.intentoms.agent.mandate.MandateService;
import ai.intentoms.agent.registry.AgentLimits;
import ai.intentoms.agent.registry.AgentRegistered;
import ai.intentoms.agent.registry.AgentRegistration;
import ai.intentoms.agent.registry.AgentRegistry;
import ai.intentoms.decision.definition.DecisionDefinitionLoader;
import ai.intentoms.finance.compliance.ComplianceRuleLoader;
import ai.intentoms.finance.compliance.DefaultComplianceService;
import ai.intentoms.finance.model.Money;
import ai.intentoms.order.capture.CatalogLoader;
import ai.intentoms.order.service.OrderTypeLoader;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantFilter;
import ai.intentoms.statemachine.service.StateModelLoader;
import ai.intentoms.testing.PostgresTestBase;
import ai.intentoms.testing.TestApplication;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration base: real HTTP server (the agent security chain runs for real), demo state models, decisions, compliance
 * rules, catalogue, order types and tenant config seeded for tenant {@code test}; helpers to register agents, issue
 * mandates and send HMAC-signed MCP / UCP requests.
 */
@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"oms.workflow.enabled=false", "oms.agent.demo-expose-secrets=true"})
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.ignore-migration-patterns=*:missing,*:future")
public abstract class AgentTestBase extends PostgresTestBase {
    public static final Path DEMO = Path.of("..", "..", "config", "tenants", "demo").toAbsolutePath().normalize();

    @Autowired protected StateModelLoader stateModels;
    @Autowired protected DecisionDefinitionLoader decisionDefinitions;
    @Autowired protected ComplianceRuleLoader complianceRules;
    @Autowired protected DefaultComplianceService complianceImpl;
    @Autowired protected CatalogLoader catalogLoader;
    @Autowired protected OrderTypeLoader orderTypeLoader;
    @Autowired protected TenantConfigStore config;
    @Autowired protected AgentRegistry registry;
    @Autowired protected MandateService mandates;
    @LocalServerPort protected int port;

    protected final RestClient http = RestClient.builder().requestFactory(new JdkClientHttpRequestFactory()).defaultStatusHandler(s -> true, (req, res) -> {}).build();

    @BeforeEach
    void seedDemo() {
        stateModels.loadDirectory(DEMO.resolve("state-models"));
        decisionDefinitions.loadDirectory(DEMO.resolve("decisions"));
        complianceRules.loadFile(DEMO.resolve("rules/compliance.yaml"));
        complianceImpl.invalidate();
        catalogLoader.loadFile(DEMO.resolve("catalog.yaml"));
        orderTypeLoader.loadFile(DEMO.resolve("order-types.yaml"));
        Map<String, Object> tenantConfig;
        try { tenantConfig = Json.yamlMap(Files.readString(DEMO.resolve("tenant-config.yaml"))); } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        tenantConfig.forEach((k, v) -> config.set(k, v instanceof String s ? Json.write(s) : v));
    }

    /** A registered agent with its secret. */
    public record TestAgent(String id, String secret, String apiKey, int tier) {}

    protected TestAgent registerAgent(int tier, List<String> allowed, BigDecimal maxOrder, Integer ratePerMinute, List<String> confirm, String callbackUrl) {
        String id = "agt-" + Ids.shortId();
        AgentRegistered r = registry.register(new AgentRegistration(id, "Test agent " + id, tier >= 3 ? "mcp" : "ucp", tier,
            new AgentLimits(allowed, maxOrder, new BigDecimal("100"), ratePerMinute, confirm), callbackUrl, null));
        assertThat(r.secret()).startsWith("ags_");
        return new TestAgent(id, r.secret(), r.apiKey(), tier);
    }

    protected TestAgent shopper() {
        return registerAgent(2, List.of("order_get", "order_search", "promise", "order_create", "order_modify", "order_cancel", "return_create", "inventory_check", "decision_why"),
            new BigDecimal("1500"), 600, List.of(), null);
    }

    protected Mandate mandate(TestAgent agent, String buyerRef, String limit, String instruction) {
        return mandates.create(new MandateCreate(null, agent.id(), buyerRef, Map.of("merchant", "demo", "categories", List.of("apparel"), "maxItems", 5), Money.usd(limit),
            instruction, null, "tok_agent_" + Ids.shortId(), null, null));
    }

    protected String signedHeaderTimestamp() { return String.valueOf(Instant.now().getEpochSecond()); }

    /** Sends an HMAC-signed request as the agent. */
    protected ResponseEntity<String> signed(TestAgent agent, HttpMethod method, String path, Object body) {
        return signed(agent.id(), agent.secret(), signedHeaderTimestamp(), method, path, body, Map.of());
    }

    protected ResponseEntity<String> signed(String agentId, String secret, String timestamp, HttpMethod method, String path, Object body, Map<String, String> extraHeaders) {
        byte[] bytes = body == null ? new byte[0] : (body instanceof String s ? s : Json.write(body)).getBytes(StandardCharsets.UTF_8);
        String sig = HmacSigner.sign(secret, timestamp, method.name(), path, bytes);
        RestClient.RequestBodySpec spec = http.method(method).uri("http://localhost:" + port + path)
            .header(HmacSigner.HEADER_AGENT, agentId).header(HmacSigner.HEADER_TIMESTAMP, timestamp).header(HmacSigner.HEADER_SIGNATURE, sig)
            .header(TenantFilter.HEADER, TENANT).header("Content-Type", "application/json").header("Accept", "application/json");
        extraHeaders.forEach(spec::header);
        if (bytes.length > 0) spec.body(bytes);
        return spec.retrieve().toEntity(String.class);
    }

    protected ResponseEntity<String> apiKey(TestAgent agent, HttpMethod method, String path, Object body) {
        RestClient.RequestBodySpec spec = http.method(method).uri("http://localhost:" + port + path).header("X-Api-Key", agent.apiKey()).header("Content-Type", "application/json");
        if (body != null) spec.body(Json.write(body));
        return spec.retrieve().toEntity(String.class);
    }

    protected Map<String, Object> rpc(Object id, String method, Map<String, Object> params) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("method", method);
        if (params != null) m.put("params", params);
        return m;
    }

    protected Map<String, Object> toolCall(Object id, String tool, Map<String, Object> args) { return rpc(id, "tools/call", Map.of("name", tool, "arguments", args)); }

    /** Signed MCP call; returns the parsed JSON-RPC response. */
    protected JsonNode mcp(TestAgent agent, Map<String, Object> request) {
        ResponseEntity<String> r = signed(agent, HttpMethod.POST, "/mcp", request);
        assertThat(r.getStatusCode().value()).as("MCP HTTP status, body=" + r.getBody()).isEqualTo(200);
        return Json.tree(r.getBody());
    }

    protected JsonNode structured(JsonNode rpcResponse) {
        assertThat(rpcResponse.has("error")).as("rpc error: " + rpcResponse).isFalse();
        return rpcResponse.path("result").path("structuredContent");
    }

    /** Two-line apparel order to San Francisco as an MCP order_create argument. */
    protected Map<String, Object> orderArgs(String channelOrderId, String customerRef, String sku, int qty, String price, List<Map<String, Object>> notes) {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("channelOrderId", channelOrderId);
        order.put("orderType", "standard_ship");
        order.put("customerRef", customerRef);
        order.put("customer", Map.of("ref", customerRef, "name", "Jane Doe", "email", "jane@example.com", "tier", "gold"));
        order.put("lines", List.of(Map.of("lineId", "L1", "sku", sku, "qty", qty, "unitPrice", Map.of("amount", new BigDecimal(price), "currency", "USD"))));
        order.put("addresses", List.of(Map.of("type", "ship_to", "name", "Jane Doe", "line1", "1 Market St", "city", "San Francisco", "state", "CA", "zip", "94105", "country", "US")));
        order.put("charges", List.of(Map.of("type", "shipping", "code", "ground", "amount", Map.of("amount", new BigDecimal("6.00"), "currency", "USD"))));
        order.put("shipMethod", "ground");
        if (notes != null) order.put("notes", notes);
        return order;
    }

    protected List<Map<String, Object>> orderEvents(String orderId, String type) {
        return jdbc.sql("select type, payload::text as payload from event where tenant_id = :t and aggregate_type = 'order' and aggregate_id = :id and type = :type order by sequence")
            .param("t", TENANT).param("id", orderId).param("type", type).query().listOfRows();
    }
}
