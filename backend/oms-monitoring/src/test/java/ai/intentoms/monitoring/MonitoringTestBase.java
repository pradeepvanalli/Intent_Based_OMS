package ai.intentoms.monitoring;

import ai.intentoms.decision.definition.DecisionDefinitionLoader;
import ai.intentoms.finance.model.Money;
import ai.intentoms.monitoring.exception.ExceptionTaxonomyLoader;
import ai.intentoms.monitoring.playbook.PlaybookLoader;
import ai.intentoms.monitoring.sla.SlaLoader;
import ai.intentoms.order.model.Customer;
import ai.intentoms.order.model.FulfilmentGroup;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.model.OrderLine;
import ai.intentoms.order.model.Totals;
import ai.intentoms.order.store.OrderRepository;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.time.TimeSource;
import ai.intentoms.statemachine.StateMachine;
import ai.intentoms.statemachine.model.TransitionRequest;
import ai.intentoms.statemachine.service.StateModelLoader;
import ai.intentoms.testing.PostgresTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Shared context for monitoring integration tests: schedulers off, demo decisions / state models / taxonomy / playbooks /
 * SLAs seeded for tenant "test", helpers to create orders whose header and lines live in the state machine.
 */
@SpringBootTest(classes = MonitoringTestApplication.class, properties = {"oms.monitoring.enabled=false", "oms.workflow.enabled=false"})
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.ignore-migration-patterns=*:missing,*:future")
public abstract class MonitoringTestBase extends PostgresTestBase {
    protected static final Path DEMO = Path.of("..", "..", "config", "tenants", "demo").toAbsolutePath().normalize();
    protected static final Actor SYSTEM = Actor.system("test");

    @Autowired protected DecisionDefinitionLoader decisionLoader;
    @Autowired protected StateModelLoader stateModelLoader;
    @Autowired protected ExceptionTaxonomyLoader taxonomyLoader;
    @Autowired protected PlaybookLoader playbookLoader;
    @Autowired protected SlaLoader slaLoader;
    @Autowired protected OrderRepository orders;
    @Autowired protected StateMachine machine;
    @Autowired protected TenantConfigStore config;
    @Autowired protected TimeSource time;

    @BeforeEach
    void seed() {
        decisionLoader.loadDirectory(DEMO.resolve("decisions"));
        stateModelLoader.loadDirectory(DEMO.resolve("state-models"));
        taxonomyLoader.loadFile(DEMO.resolve("exception-taxonomy.yaml"));
        playbookLoader.loadFile(DEMO.resolve("playbooks.yaml"));
        slaLoader.loadFile(DEMO.resolve("sla.yaml"));
        config.set("alerts.dedupe_minutes", 15);
        config.set("sourcing.margin_floor", 0.12);
    }

    @AfterEach
    void resetClock() { time.reset(); }

    /** Insert an order with one line of {@code qty} and initialise header + line state machines (header rolls up to {@code released} when asked). */
    protected Order newOrder(String channel, String status, boolean release) {
        String id = "O" + Ids.shortId();
        OrderLine line = new OrderLine("L1", "SKU-1", "Widget", 2, Money.usd("25.00"), Money.usd("0"), Money.usd("0"), "fg_" + id, null, Map.of("weight_kg", 1.0));
        FulfilmentGroup group = new FulfilmentGroup("fg_" + id, "DC-EAST", "ship_as_available", List.of(new FulfilmentGroup.Allocation("L1", "SKU-1", 2)), LocalDate.now().plusDays(3), 0.9,
            "ground", Money.usd("8.00"), "planned", null, null, Map.of());
        Totals totals = new Totals(Money.usd("50.00"), Money.usd("0"), Money.usd("8.00"), Money.usd("0"), Money.usd("0"), Money.usd("0"), Money.usd("58.00"));
        Order o = new Order(id, channel, "CH-" + id, "standard_ship", "cust-1", new Customer("cust-1", null, null, null, "gold", false, Map.of()), List.of(line), List.of(), List.of(), List.of(),
            List.of(), List.of(group), null, null, totals, null, "order.standard_ship", 1, status, "ground", "USD", Map.of(), Instant.now(), null, null, 0);
        Order saved = orders.insert(o);
        machine.initialize("order", id, "order.standard_ship", null, 1, Map.of("channel", channel), SYSTEM);
        machine.initialize("order_line", id + ":L1", "order_line.standard_ship", null, 2, Map.of(), SYSTEM);
        if (release) {
            machine.fire(TransitionRequest.of("order_line", id + ":L1", "event.order_validated", Map.of(), SYSTEM));
            machine.fire(TransitionRequest.of("order_line", id + ":L1", "decision.sourcing_plan", Map.of("plan", Map.of("margin", 0.3, "result", "ok"), "approval", Map.of("present", false)), SYSTEM));
        }
        return saved;
    }

    /** Pretend the header entered its current state {@code age} ago. */
    protected void backdateState(String orderId, Duration age) {
        Timestamp since = Timestamp.from(Instant.now().minus(age));
        jdbc.sql("update entity_state_qty set since = :s where tenant_id = :t and entity_type = 'order' and entity_id = :id").param("s", since).param("t", TENANT).param("id", orderId).update();
        jdbc.sql("update entity_state set updated_at = :s where tenant_id = :t and entity_type = 'order' and entity_id = :id").param("s", since).param("t", TENANT).param("id", orderId).update();
    }

    protected List<Map<String, Object>> events(String aggregateType, String aggregateId, String type) {
        return jdbc.sql("select type, payload::text as payload from event where tenant_id = :t and aggregate_type = :at and aggregate_id = :aid and type = :type order by sequence")
            .param("t", TENANT).param("at", aggregateType).param("aid", aggregateId).param("type", type).query().listOfRows();
    }
}
