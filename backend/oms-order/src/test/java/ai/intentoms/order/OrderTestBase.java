package ai.intentoms.order;

import ai.intentoms.decision.definition.DecisionDefinitionLoader;
import ai.intentoms.finance.compliance.ComplianceRuleLoader;
import ai.intentoms.finance.compliance.DefaultComplianceService;
import ai.intentoms.finance.model.Money;
import ai.intentoms.order.capture.CaptureRequest;
import ai.intentoms.order.capture.CatalogLoader;
import ai.intentoms.order.service.OrderTypeLoader;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.json.Json;
import ai.intentoms.statemachine.service.StateModelLoader;
import ai.intentoms.testing.PostgresTestBase;
import ai.intentoms.testing.TestApplication;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Integration base: Postgres, demo state models, decisions, compliance rules, catalogue and order types seeded for tenant
 * "test"; workflow pollers off so tests stay deterministic.
 */
@SpringBootTest(classes = TestApplication.class, properties = {"oms.workflow.enabled=false"})
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.ignore-migration-patterns=*:missing,*:future")
public abstract class OrderTestBase extends PostgresTestBase {
    public static final Path DEMO = Path.of("..", "..", "config", "tenants", "demo").toAbsolutePath().normalize();

    @Autowired protected StateModelLoader stateModels;
    @Autowired protected DecisionDefinitionLoader decisionDefinitions;
    @Autowired protected ComplianceRuleLoader complianceRules;
    @Autowired protected DefaultComplianceService complianceImpl;
    @Autowired protected CatalogLoader catalogLoader;
    @Autowired protected OrderTypeLoader orderTypeLoader;
    @Autowired protected TenantConfigStore config;

    @BeforeEach
    void seedDemoConfig() {
        stateModels.loadDirectory(DEMO.resolve("state-models"));
        decisionDefinitions.loadDirectory(DEMO.resolve("decisions"));
        complianceRules.loadFile(DEMO.resolve("rules/compliance.yaml"));
        complianceImpl.invalidate();
        catalogLoader.loadFile(DEMO.resolve("catalog.yaml"));
        orderTypeLoader.loadFile(DEMO.resolve("order-types.yaml"));
        Map<String, Object> tenantConfig = Json.yamlMap(readDemo("tenant-config.yaml"));
        tenantConfig.forEach((k, v) -> config.set(k, v instanceof String s ? Json.write(s) : v));
    }

    private static String readDemo(String file) {
        try { return java.nio.file.Files.readString(DEMO.resolve(file)); } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }

    protected static CaptureRequest.LineInput line(String id, String sku, int qty, String price) {
        return new CaptureRequest.LineInput(id, sku, null, qty, Money.usd(price), null, null, null);
    }

    protected static CaptureRequest.AddressInput shipTo(String line1, String city, String state, String zip) {
        return new CaptureRequest.AddressInput("ship_to", "Jane Doe", line1, null, city, state, zip, "US", "+14155550100", null);
    }

    protected static CaptureRequest.TenderInput card(String amount) { return new CaptureRequest.TenderInput("card", "tok_visa_" + amount, Money.usd(amount), Map.of()); }

    protected static CaptureRequest.CustomerInput jane() {
        return new CaptureRequest.CustomerInput("cust-jane", "Jane Doe", "jane@example.com", "+14155550100", "gold", false, null, Map.of("split_acceptance_rate", 0.8, "orders_12m", 7));
    }

    /** Two-line web order to California paid by card, with a free-text note. */
    protected static CaptureRequest webOrder(String channelOrderId, String... notes) {
        List<CaptureRequest.NoteInput> noteInputs = java.util.Arrays.stream(notes).map(t -> new CaptureRequest.NoteInput("customer", "jane@example.com", t, false)).toList();
        return new CaptureRequest("web", channelOrderId, "standard_ship", "cust-jane", jane(),
            List.of(line("L1", "TEE-BLK-M", 2, "28.00"), line("L2", "CAP-NVY", 1, "32.00")),
            List.of(shipTo("1 Market St", "San Francisco", "CA", "94105")),
            List.of(new CaptureRequest.ChargeInput("shipping", "ground", Money.usd("6.00"), "Standard shipping")),
            List.of(card("150.00")), noteInputs, List.of(), "ground", null, "USD", Map.of("gift_wrap", false), null);
    }

    protected static CaptureRequest withLines(CaptureRequest base, List<CaptureRequest.LineInput> lines) {
        return new CaptureRequest(base.channel(), base.channelOrderId(), base.orderType(), base.customerRef(), base.customer(), lines, base.addresses(), base.charges(), base.tenders(), base.notes(),
            base.intentChoices(), base.shipMethod(), base.placedAt(), base.currency(), base.extensions(), base.provenance());
    }

    protected static CaptureRequest withShipMethod(CaptureRequest base, String method) {
        return new CaptureRequest(base.channel(), base.channelOrderId(), base.orderType(), base.customerRef(), base.customer(), base.lines(), base.addresses(), base.charges(), base.tenders(), base.notes(),
            base.intentChoices(), method, base.placedAt(), base.currency(), base.extensions(), base.provenance());
    }

    protected static CaptureRequest withAddresses(CaptureRequest base, List<CaptureRequest.AddressInput> addresses) {
        return new CaptureRequest(base.channel(), base.channelOrderId(), base.orderType(), base.customerRef(), base.customer(), base.lines(), addresses, base.charges(), base.tenders(), base.notes(),
            base.intentChoices(), base.shipMethod(), base.placedAt(), base.currency(), base.extensions(), base.provenance());
    }

    protected static CaptureRequest withProvenance(CaptureRequest base, CaptureRequest.ProvenanceInput p) {
        return new CaptureRequest("agent", base.channelOrderId(), base.orderType(), base.customerRef(), base.customer(), base.lines(), base.addresses(), base.charges(),
            List.of(new CaptureRequest.TenderInput("agent_token", "mandate-" + p.mandateId(), Money.usd("0"), Map.of())), base.notes(),
            base.intentChoices(), base.shipMethod(), base.placedAt(), base.currency(), base.extensions(), p);
    }

    protected static CaptureRequest withIntentChoices(CaptureRequest base, List<String> choices) {
        return new CaptureRequest(base.channel(), base.channelOrderId(), base.orderType(), base.customerRef(), base.customer(), base.lines(), base.addresses(), base.charges(), base.tenders(), base.notes(),
            choices, base.shipMethod(), base.placedAt(), base.currency(), base.extensions(), base.provenance());
    }

    protected List<Map<String, Object>> events(String aggregateId, String type) {
        return jdbc.sql("select type, payload::text as payload from event where tenant_id = :t and aggregate_type = 'order' and aggregate_id = :id and type = :type order by sequence")
            .param("t", TENANT).param("id", aggregateId).param("type", type).query().listOfRows();
    }
}
