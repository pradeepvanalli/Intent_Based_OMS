package ai.intentoms.copilot;

import ai.intentoms.decision.definition.DecisionDefinitionLoader;
import ai.intentoms.finance.compliance.ComplianceRuleLoader;
import ai.intentoms.finance.compliance.DefaultComplianceService;
import ai.intentoms.finance.model.Money;
import ai.intentoms.monitoring.exception.ExceptionTaxonomyLoader;
import ai.intentoms.monitoring.playbook.PlaybookLoader;
import ai.intentoms.monitoring.sla.SlaLoader;
import ai.intentoms.order.capture.CaptureRequest;
import ai.intentoms.order.capture.CatalogLoader;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.service.OrderService;
import ai.intentoms.order.service.OrderTypeLoader;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.json.Json;
import ai.intentoms.sourcing.node.NodeLoader;
import ai.intentoms.statemachine.service.StateModelLoader;
import ai.intentoms.testing.PostgresTestBase;
import ai.intentoms.testing.TestApplication;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Integration base with the demo tenant configuration seeded (state models, decisions, compliance rules, catalogue,
 * order types, SLAs, playbooks, taxonomy, nodes, tenant config). Subclasses decide how the copilot reaches a model.
 */
@SpringBootTest(classes = TestApplication.class, properties = {"oms.workflow.enabled=false", "oms.monitoring.enabled=false"})
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.ignore-migration-patterns=*:missing,*:future")
public abstract class SeededTestBase extends PostgresTestBase {
    public static final Path DEMO = Path.of("..", "..", "config", "tenants", "demo").toAbsolutePath().normalize();
    @DynamicPropertySource
    static void copilotPaths(DynamicPropertyRegistry r) {
        r.add("oms.copilot.templates-dir", () -> Path.of("..", "..", "config", "copilot").toAbsolutePath().normalize().toString());
        r.add("oms.copilot.starter-dir", () -> DEMO.toString());
        r.add("oms.copilot.model", () -> "anthropic/claude-sonnet-4.5");
    }

    @Autowired protected StateModelLoader stateModels;
    @Autowired protected DecisionDefinitionLoader decisionDefinitions;
    @Autowired protected ComplianceRuleLoader complianceRules;
    @Autowired protected DefaultComplianceService complianceImpl;
    @Autowired protected CatalogLoader catalogLoader;
    @Autowired protected OrderTypeLoader orderTypeLoader;
    @Autowired protected SlaLoader slaLoader;
    @Autowired protected PlaybookLoader playbookLoader;
    @Autowired protected ExceptionTaxonomyLoader taxonomyLoader;
    @Autowired protected NodeLoader nodeLoader;
    @Autowired protected TenantConfigStore config;
    @Autowired protected OrderService orders;

    @BeforeEach
    void seedDemoConfig() {
        stateModels.loadDirectory(DEMO.resolve("state-models"));
        decisionDefinitions.loadDirectory(DEMO.resolve("decisions"));
        complianceRules.loadFile(DEMO.resolve("rules/compliance.yaml"));
        complianceImpl.invalidate();
        catalogLoader.loadFile(DEMO.resolve("catalog.yaml"));
        orderTypeLoader.loadFile(DEMO.resolve("order-types.yaml"));
        slaLoader.loadFile(DEMO.resolve("sla.yaml"));
        playbookLoader.loadFile(DEMO.resolve("playbooks.yaml"));
        taxonomyLoader.loadFile(DEMO.resolve("exception-taxonomy.yaml"));
        nodeLoader.loadFile(DEMO.resolve("nodes.yaml"));
        Map<String, Object> tenantConfig = Json.yamlMap(readDemo("tenant-config.yaml"));
        tenantConfig.forEach((k, v) -> config.set(k, v instanceof String s ? Json.write(s) : v));
    }

    protected static String readDemo(String file) {
        try { return Files.readString(DEMO.resolve(file)); } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }

    protected static CaptureRequest.LineInput line(String id, String sku, int qty, String price) { return new CaptureRequest.LineInput(id, sku, null, qty, Money.usd(price), null, null, null); }

    protected static CaptureRequest webOrder(String channel, String state, String zip, String city, List<CaptureRequest.LineInput> lines, String... notes) {
        List<CaptureRequest.NoteInput> noteInputs = java.util.Arrays.stream(notes).map(t -> new CaptureRequest.NoteInput("customer", "jane@example.com", t, false)).toList();
        return new CaptureRequest(channel, "W-" + Ids.shortId(), "standard_ship", "cust-jane",
            new CaptureRequest.CustomerInput("cust-jane", "Jane Doe", "jane@example.com", "+14155550100", "gold", false, null, Map.of("split_acceptance_rate", 0.8, "orders_12m", 7)),
            lines, List.of(new CaptureRequest.AddressInput("ship_to", "Jane Doe", "1 Market St", null, city, state, zip, "US", "+14155550100", null)),
            List.of(new CaptureRequest.ChargeInput("shipping", "ground", Money.usd("6.00"), "Standard shipping")),
            List.of(new CaptureRequest.TenderInput("card", "tok_visa_4242", Money.usd("150.00"), Map.of())), noteInputs, List.of(), "ground", null, "USD", Map.of(), null);
    }

    /** Captures a two-line web order shipping to the given state. */
    protected Order captureOrder(String state, String... notes) {
        String zip = state.equals("TX") ? "78701" : "94105";
        String city = state.equals("TX") ? "Austin" : "San Francisco";
        return orders.capture(webOrder("web", state, zip, city, List.of(line("L1", "TEE-BLK-M", 2, "28.00"), line("L2", "CAP-NVY", 1, "32.00")), notes), null);
    }

    protected List<Map<String, Object>> configEvents(String aggregateId, String type) {
        return jdbc.sql("select type, payload::text as payload from event where tenant_id = :t and aggregate_type = 'config' and aggregate_id = :id and type = :type order by sequence")
            .param("t", TENANT).param("id", aggregateId).param("type", type).query().listOfRows();
    }
}
