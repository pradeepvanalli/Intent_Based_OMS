package ai.intentoms.returns;

import ai.intentoms.decision.definition.DecisionDefinitionLoader;
import ai.intentoms.finance.model.Money;
import ai.intentoms.finance.payment.PaymentService;
import ai.intentoms.order.model.*;
import ai.intentoms.order.store.OrderRepository;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.security.Principal;
import ai.intentoms.platform.security.PrincipalAuthentication;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.platform.time.TimeSource;
import ai.intentoms.statemachine.StateMachine;
import ai.intentoms.statemachine.model.TransitionRequest;
import ai.intentoms.statemachine.model.TransitionResult;
import ai.intentoms.statemachine.service.StateModelLoader;
import ai.intentoms.testing.PostgresTestBase;
import ai.intentoms.workflow.task.HumanTask;
import ai.intentoms.workflow.task.HumanTaskService;
import ai.intentoms.workflow.task.TaskQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration base: a fresh tenant per test (history, spend ledger and config never leak), demo state models and
 * decisions seeded, and a fixture that captures an order whose lines are moved to {@code delivered} through the real
 * state machine and paid through the mock PSP so refunds work.
 */
@SpringBootTest(classes = ReturnsTestApplication.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.ignore-migration-patterns=*:missing,*:future")
public abstract class ReturnsTestBase extends PostgresTestBase {
    public static final Path DEMO_STATE_MODELS = Path.of("..", "..", "config", "tenants", "demo", "state-models").toAbsolutePath().normalize();
    public static final Path DEMO_DECISIONS = Path.of("..", "..", "config", "tenants", "demo", "decisions").toAbsolutePath().normalize();
    public static final Path RELAXED_DECISIONS = Path.of("src", "test", "resources", "decisions-relaxed").toAbsolutePath().normalize();
    protected static final Actor SYSTEM = Actor.system("test");

    @Autowired protected StateModelLoader stateModels;
    @Autowired protected DecisionDefinitionLoader decisionLoader;
    @Autowired protected StateMachine machine;
    @Autowired protected OrderRepository orders;
    @Autowired protected PaymentService payments;
    @Autowired protected TenantConfigStore config;
    @Autowired protected EventStore events;
    @Autowired protected HumanTaskService tasks;
    @Autowired protected TimeSource time;

    protected String tenant;

    @BeforeEach
    void freshTenant() {
        tenant = "ret_" + Ids.shortId();
        jdbc.sql("insert into tenant(tenant_id, name) values (:t, :t) on conflict do nothing").param("t", tenant).update();
        TenantContext.set(tenant);
        SecurityContextHolder.getContext().setAuthentication(new PrincipalAuthentication(
            new Principal(Principal.Type.USER, "tester", tenant, Set.of("admin"), Set.of("*"), 3)));
        stateModels.loadDirectory(DEMO_STATE_MODELS);
        decisionLoader.loadDirectory(DEMO_DECISIONS);
        config.set("sourcing.margin_floor", 0.12);
    }

    @AfterEach
    void resetClock() { time.reset(); }

    /** Makes the heuristic provider land in the ACT band for return_risk (see src/test/resources/decisions-relaxed). */
    protected void useRelaxedRiskBands() { decisionLoader.loadDirectory(RELAXED_DECISIONS); }

    protected static OrderLine line(String lineId, String sku, String price, int qty, String tax, Map<String, Object> attributes) {
        return new OrderLine(lineId, sku, sku + " description", qty, Money.usd(price), Money.zero("USD"), Money.usd(tax), null, null, attributes);
    }

    /** Captures an order, drives every line to {@code delivered} and captures the payment (card tender). */
    protected Order newDeliveredOrder(String customerRef, String email, List<OrderLine> lines, Money shipping) {
        String id = "ord_" + Ids.shortId();
        Instant now = Instant.now();
        Money merch = Money.zero("USD");
        Money tax = Money.zero("USD");
        for (OrderLine l : lines) { merch = merch.add(l.extendedPrice()); tax = tax.add(l.tax()); }
        Money ship = shipping == null ? Money.zero("USD") : shipping;
        Money grand = merch.add(tax).add(ship);
        Totals totals = new Totals(merch, Money.zero("USD"), ship, tax, Money.zero("USD"), Money.zero("USD"), grand);
        List<Charge> charges = ship.isPositive() ? List.of(new Charge("shipping", "GROUND", ship, "Ground shipping")) : List.of();
        Address shipTo = new Address("ship_to", "Jane Doe", "1 Main St", null, "Austin", "TX", "78701", "US", null, "residential", true, null, null);
        Order o = new Order(id, "web", "WEB-" + id, "standard_ship", customerRef, new Customer(customerRef, "Jane Doe", email, null, "gold", false, Map.of()), lines, List.of(shipTo),
            charges, List.of(new Tender("card", "tok_visa_4242", grand, "pending", null, Map.of())), List.of(), List.of(), null, null, totals, null, "order.standard_ship", 1, "created",
            "ground", "USD", Map.of(), now, now, now, 0);
        orders.insert(o);
        machine.initialize("order", id, "order.standard_ship", null, 1, Map.of("channel", "web"), SYSTEM);
        for (OrderLine l : lines) {
            String eid = id + ":" + l.lineId();
            machine.initialize("order_line", eid, "order_line.standard_ship", null, l.qty(), Map.of("sku", l.sku()), SYSTEM);
            fireLine(eid, "event.order_validated", Map.of());
            fireLine(eid, "decision.sourcing_plan", Map.of("plan", Map.of("margin", 0.5, "result", "ok")));
            fireLine(eid, "event.pick_started", Map.of());
            fireLine(eid, "event.packed", Map.of());
            fireLine(eid, "event.shipped", Map.of());
            fireLine(eid, "event.delivered", Map.of());
            assertThat(machine.status("order_line", eid).state()).isEqualTo("delivered");
        }
        payments.authorize(id, grand, "tok_visa_4242", "card", null);
        payments.capture(id, grand, "shipped");
        return orders.get(id);
    }

    protected void fireLine(String entityId, String trigger, Map<String, Object> vars) {
        TransitionResult r = machine.fire(new TransitionRequest("order_line", entityId, trigger, null, null, vars, SYSTEM, entityId, null));
        assertThat(r.applied()).as(trigger + " on " + entityId + " blocked by " + r.blockedBy()).isTrue();
    }

    protected List<String> eventTypes(String rmaId) { return events.load("return", rmaId).stream().map(Event::type).toList(); }

    protected Event lastEvent(String rmaId, String type) {
        List<Event> all = events.load("return", rmaId).stream().filter(e -> e.type().equals(type)).toList();
        assertThat(all).as("event " + type + " on " + rmaId).isNotEmpty();
        return all.get(all.size() - 1);
    }

    protected List<HumanTask> tasksFor(String queue, String rmaId) {
        return tasks.list(new TaskQuery(queue, null, null, "return", rmaId, null, 0, 50)).items();
    }
}
