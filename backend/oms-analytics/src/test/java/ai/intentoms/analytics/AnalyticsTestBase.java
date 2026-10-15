package ai.intentoms.analytics;

import ai.intentoms.decision.definition.DecisionDefinitionLoader;
import ai.intentoms.finance.model.Money;
import ai.intentoms.monitoring.exception.ExceptionTaxonomyLoader;
import ai.intentoms.monitoring.playbook.PlaybookLoader;
import ai.intentoms.order.model.Customer;
import ai.intentoms.order.model.FulfilmentGroup;
import ai.intentoms.order.model.MarginSnapshot;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.model.OrderLine;
import ai.intentoms.order.model.Totals;
import ai.intentoms.order.store.OrderRepository;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
import ai.intentoms.platform.time.TimeSource;
import ai.intentoms.statemachine.service.StateModelLoader;
import ai.intentoms.testing.PostgresTestBase;
import ai.intentoms.testing.TestApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shared context for analytics integration tests: schedulers off, demo decisions / state models / exception taxonomy /
 * playbooks seeded for tenant "test", and helpers to insert orders with fulfilment groups and ledger events.
 */
@SpringBootTest(classes = TestApplication.class, properties = {"oms.analytics.enabled=false", "oms.monitoring.enabled=false", "oms.workflow.enabled=false"})
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.ignore-migration-patterns=*:missing,*:future")
public abstract class AnalyticsTestBase extends PostgresTestBase {
    protected static final Path DEMO = Path.of("..", "..", "config", "tenants", "demo").toAbsolutePath().normalize();
    protected static final Actor SYSTEM = Actor.system("test");

    @Autowired protected DecisionDefinitionLoader decisionLoader;
    @Autowired protected StateModelLoader stateModelLoader;
    @Autowired protected ExceptionTaxonomyLoader taxonomyLoader;
    @Autowired protected PlaybookLoader playbookLoader;
    @Autowired protected OrderRepository orders;
    @Autowired protected EventStore events;
    @Autowired protected TenantConfigStore config;
    @Autowired protected TimeSource time;

    @BeforeEach
    void seedDemoConfig() {
        decisionLoader.loadDirectory(DEMO.resolve("decisions"));
        stateModelLoader.loadDirectory(DEMO.resolve("state-models"));
        taxonomyLoader.loadFile(DEMO.resolve("exception-taxonomy.yaml"));
        playbookLoader.loadFile(DEMO.resolve("playbooks.yaml"));
    }

    @AfterEach
    void resetClock() { time.reset(); }

    /** A fulfilment group at {@code nodeId} with the given estimated cost and promise date. */
    protected static FulfilmentGroup group(String orderId, int idx, String nodeId, String cost, LocalDate promise) {
        return new FulfilmentGroup("fg_" + orderId + "_" + idx, nodeId, "ship_as_available", List.of(new FulfilmentGroup.Allocation("L1", "SKU-1", 1)), promise, 0.9,
            "ground", Money.usd(cost), "planned", null, null, Map.of());
    }

    /** Insert an order placed now with the given header status, groups and (optional) margin snapshot. */
    protected Order newOrder(String status, List<FulfilmentGroup> groups, MarginSnapshot margin) {
        String id = "O" + Ids.shortId();
        List<FulfilmentGroup> gs = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            FulfilmentGroup g = groups.get(i);
            gs.add(new FulfilmentGroup("fg_" + id + "_" + i, g.nodeId(), g.policy(), g.allocations(), g.promiseDate(), g.onTimeProbability(), g.shipMethod(), g.estimatedCost(), g.status(), null, null, Map.of()));
        }
        OrderLine line = new OrderLine("L1", "SKU-1", "Widget", 2, Money.usd("25.00"), Money.usd("0"), Money.usd("0"), gs.isEmpty() ? null : gs.get(0).id(), null, Map.of("weight_kg", 1.0));
        Totals totals = new Totals(Money.usd("50.00"), Money.usd("0"), Money.usd("8.00"), Money.usd("0"), Money.usd("0"), Money.usd("0"), Money.usd("58.00"));
        Order o = new Order(id, "web", "CH-" + id, "standard_ship", "cust-1", new Customer("cust-1", null, null, null, "gold", false, Map.of()), List.of(line), List.of(), List.of(), List.of(),
            List.of(), gs, null, null, totals, margin, "order.standard_ship", 1, status, "ground", "USD", Map.of(), time.now(), null, null, 0);
        return orders.insert(o);
    }

    protected static MarginSnapshot belowFloor(String approvalTaskId) {
        return new MarginSnapshot(new BigDecimal("50.00"), new BigDecimal("4.00"), 0.08, 0.12, true, approvalTaskId != null, approvalTaskId, Instant.now());
    }

    /** Append the header roll-up event the state machine writes when every line is delivered. */
    protected void delivered(String orderId) {
        events.append("order", orderId, new NewEvent("order.transitioned", Map.of("entityType", "order", "entityId", orderId, "from", "shipped", "to", "delivered", "trigger", "rollup", "qty", 1), SYSTEM));
    }

    /** Move an order's placed/captured timestamps (and its groups) to {@code day} so a test can own a day of the range. */
    protected void placedOn(String orderId, LocalDate day) {
        Timestamp ts = Timestamp.from(day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().plusSeconds(3600));
        jdbc.sql("update orders set placed_at = :ts, captured_at = :ts where tenant_id = :t and id = :id").param("ts", ts).param("t", TENANT).param("id", orderId).update();
        jdbc.sql("update fulfilment_group set created_at = :ts where tenant_id = :t and order_id = :id").param("ts", ts).param("t", TENANT).param("id", orderId).update();
    }
}
