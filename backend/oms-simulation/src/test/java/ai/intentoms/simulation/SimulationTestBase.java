package ai.intentoms.simulation;

import ai.intentoms.decision.definition.DecisionDefinitionLoader;
import ai.intentoms.finance.compliance.ComplianceRuleLoader;
import ai.intentoms.finance.compliance.DefaultComplianceService;
import ai.intentoms.finance.model.Money;
import ai.intentoms.inventory.model.InventoryPosition;
import ai.intentoms.inventory.service.InventoryService;
import ai.intentoms.order.capture.CaptureRequest;
import ai.intentoms.order.capture.CatalogLoader;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.model.OrderLine;
import ai.intentoms.order.service.IntentService;
import ai.intentoms.order.service.OrderService;
import ai.intentoms.order.service.OrderTypeLoader;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.security.Principal;
import ai.intentoms.platform.security.PrincipalAuthentication;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.sourcing.SourcingService;
import ai.intentoms.sourcing.model.SourcingResult;
import ai.intentoms.sourcing.node.NodeLoader;
import ai.intentoms.sourcing.rates.RateCardLoader;
import ai.intentoms.statemachine.StateMachine;
import ai.intentoms.statemachine.model.TransitionRequest;
import ai.intentoms.statemachine.model.TransitionResult;
import ai.intentoms.statemachine.service.StateModelLoader;
import ai.intentoms.testing.PostgresTestBase;
import ai.intentoms.testing.TestApplication;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration base: a fresh tenant per test seeded with the demo state models, decisions, compliance rules, catalogue,
 * order types, tenant config, nodes and rate card, with inventory at DC-NJ and DC-TX so New York orders source from NJ.
 * Workflow pollers are off so tests stay deterministic.
 */
@SpringBootTest(classes = TestApplication.class, properties = {"oms.workflow.enabled=false", "oms.simulation.commit-every=2", "oms.simulation.page-size=2"})
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.ignore-migration-patterns=*:missing,*:future")
public abstract class SimulationTestBase extends PostgresTestBase {
    public static final Path DEMO = Path.of("..", "..", "config", "tenants", "demo").toAbsolutePath().normalize();
    public static final Actor ACTOR = Actor.user("tester");

    @Autowired protected StateModelLoader stateModels;
    @Autowired protected DecisionDefinitionLoader decisionDefinitions;
    @Autowired protected ComplianceRuleLoader complianceRules;
    @Autowired protected DefaultComplianceService complianceImpl;
    @Autowired protected CatalogLoader catalogLoader;
    @Autowired protected OrderTypeLoader orderTypeLoader;
    @Autowired protected NodeLoader nodeLoader;
    @Autowired protected RateCardLoader rateCardLoader;
    @Autowired protected TenantConfigStore config;
    @Autowired protected InventoryService inventory;
    @Autowired protected OrderService orders;
    @Autowired protected IntentService intents;
    @Autowired protected SourcingService sourcing;
    @Autowired protected StateMachine stateMachine;
    protected String tenant;

    @BeforeEach
    void seedTenant() {
        tenant = "sim_" + Ids.shortId();
        jdbc.sql("insert into tenant(tenant_id, name) values (:t, :t) on conflict do nothing").param("t", tenant).update();
        TenantContext.set(tenant);
        SecurityContextHolder.getContext().setAuthentication(new PrincipalAuthentication(new Principal(Principal.Type.USER, "tester", tenant, Set.of("admin"), Set.of("*"), 3)));
        stateModels.loadDirectory(DEMO.resolve("state-models"));
        decisionDefinitions.loadDirectory(DEMO.resolve("decisions"));
        complianceRules.loadFile(DEMO.resolve("rules/compliance.yaml"));
        complianceImpl.invalidate();
        catalogLoader.loadFile(DEMO.resolve("catalog.yaml"));
        orderTypeLoader.loadFile(DEMO.resolve("order-types.yaml"));
        nodeLoader.loadFile(DEMO.resolve("nodes.yaml"));
        rateCardLoader.loadFile(DEMO.resolve("shipping-rates.yaml"));
        Map<String, Object> tenantConfig = Json.yamlMap(readDemo("tenant-config.yaml"));
        tenantConfig.forEach((k, v) -> config.set(k, v instanceof String s ? Json.write(s) : v));
        for (String sku : List.of("TEE-BLK-M", "CAP-NVY", "HOOD-GRY-M", "SOCK-3PK")) { stock(sku, "DC-NJ", 500); stock(sku, "DC-TX", 500); }
    }

    private static String readDemo(String file) {
        try { return java.nio.file.Files.readString(DEMO.resolve(file)); } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }

    protected void stock(String sku, String nodeId, int qty) { inventory.upsert(List.of(InventoryPosition.onHand(sku, nodeId, qty)), "test"); }

    protected static CaptureRequest.LineInput line(String id, String sku, int qty, String price) {
        return new CaptureRequest.LineInput(id, sku, null, qty, Money.usd(price), null, null, null);
    }

    /** Two-line web order to New York paid by card, with free-text notes. */
    protected static CaptureRequest webOrder(String channelOrderId, String... notes) {
        List<CaptureRequest.NoteInput> noteInputs = java.util.Arrays.stream(notes).map(t -> new CaptureRequest.NoteInput("customer", "jane@example.com", t, false)).toList();
        return new CaptureRequest("web", channelOrderId, "standard_ship", "cust-jane",
            new CaptureRequest.CustomerInput("cust-jane", "Jane Doe", "jane@example.com", "+14155550100", "gold", false, null, Map.of("split_acceptance_rate", 0.8, "orders_12m", 7)),
            List.of(line("L1", "TEE-BLK-M", 2, "28.00"), line("L2", "CAP-NVY", 1, "32.00")),
            List.of(new CaptureRequest.AddressInput("ship_to", "Jane Doe", "5th Ave", null, "New York", "NY", "10001", "US", "+14155550100", null)),
            List.of(new CaptureRequest.ChargeInput("shipping", "ground", Money.usd("6.00"), "Standard shipping")),
            List.of(new CaptureRequest.TenderInput("card", "tok_visa_150", Money.usd("150.00"), Map.of())), noteInputs, List.of(), "ground", null, "USD", Map.of(), null);
    }

    /** Capture, infer intent, source and validate every line (the transitions the orchestration workflow would fire). */
    protected Order sourcedOrder(String note) {
        Order o = orders.capture(webOrder("W-" + Ids.shortId(), note), null);
        intents.inferIntent(o);
        SourcingResult r = sourcing.source(o.id(), ACTOR);
        assertThat(r.status()).isEqualTo(SourcingResult.Status.SOURCED);
        for (OrderLine l : o.lines()) {
            TransitionResult t = stateMachine.fire(new TransitionRequest("order_line", o.id() + ":" + l.lineId(), "event.order_validated", null, null, Map.of(), ACTOR, o.id(), null));
            assertThat(t.applied()).as("event.order_validated on " + l.lineId()).isTrue();
        }
        return orders.get(o.id());
    }

    protected List<Map<String, Object>> records(String orderId, String mode) {
        return jdbc.sql("select id, decision, version, mode, band, action from decision_record where tenant_id = :t and subject_type = 'order' and subject_id = :s and mode = :m order by created_at")
            .param("t", tenant).param("s", orderId).param("m", mode).query().listOfRows();
    }
}
