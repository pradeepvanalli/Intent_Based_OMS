package ai.intentoms.orchestration;

import ai.intentoms.finance.model.Money;
import ai.intentoms.finance.payment.PaymentService;
import ai.intentoms.inventory.service.ReservationService;
import ai.intentoms.orchestration.integration.InboundEventService;
import ai.intentoms.orchestration.seed.DemoSeeder;
import ai.intentoms.orchestration.shipment.ShipmentService;
import ai.intentoms.order.capture.CaptureRequest;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.service.OrderService;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.OutboxRelay;
import ai.intentoms.platform.security.Principal;
import ai.intentoms.platform.security.PrincipalAuthentication;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.platform.time.TimeSource;
import ai.intentoms.statemachine.StateMachine;
import ai.intentoms.statemachine.model.EntityStatus;
import ai.intentoms.testing.PostgresTestBase;
import ai.intentoms.testing.TestApplication;
import ai.intentoms.workflow.engine.WorkflowWorker;
import ai.intentoms.workflow.model.WorkflowEngine;
import ai.intentoms.workflow.model.WorkflowStatus;
import ai.intentoms.workflow.model.WorkflowView;
import ai.intentoms.workflow.task.HumanTaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration base: a fresh tenant per test seeded through the same loaders the {@link DemoSeeder} uses
 * ({@code seedConfig} + {@code seedInventory}), workflow pollers and outbox relay off so steps are driven with
 * {@code WorkflowWorker.runOnce()} and events with {@code OutboxRelay.drainBatch()}.
 */
@SpringBootTest(classes = TestApplication.class, properties = {"oms.workflow.enabled=false", "oms.monitoring.enabled=false", "oms.demo.seed=false", "oms.config-dir=../../config",
    "oms.orchestration.autonomy.scheduler-enabled=false"})
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.ignore-migration-patterns=*:missing,*:future")
public abstract class OrchestrationTestBase extends PostgresTestBase {
    protected static final Actor SYSTEM = Actor.system("test");

    @Autowired protected DemoSeeder seeder;
    @Autowired protected OrderService orders;
    @Autowired protected WorkflowEngine engine;
    @Autowired protected WorkflowWorker worker;
    @Autowired protected OutboxRelay outbox;
    @Autowired protected StateMachine machine;
    @Autowired protected PaymentService payments;
    @Autowired protected ReservationService reservations;
    @Autowired protected ShipmentService shipments;
    @Autowired protected InboundEventService inbound;
    @Autowired protected HumanTaskService tasks;
    @Autowired protected TenantConfigStore config;
    @Autowired protected TimeSource time;

    protected String tenant;

    @BeforeEach
    void freshTenant() {
        tenant = "orc_" + Ids.shortId();
        jdbc.sql("insert into tenant(tenant_id, name) values (:t, :t) on conflict do nothing").param("t", tenant).update();
        bind();
        seeder.seedConfig();
        seeder.seedInventory();
    }

    /** Binds the test tenant and an admin principal (the worker restores whatever the thread had bound). */
    protected void bind() {
        TenantContext.set(tenant);
        SecurityContextHolder.getContext().setAuthentication(new PrincipalAuthentication(new Principal(Principal.Type.USER, "tester", tenant, Set.of("admin"), Set.of("*"), 3)));
    }

    @AfterEach
    void resetClock() { time.reset(); }

    // ---- driving -------------------------------------------------------------------------------------------------

    /** Runs due steps and drains the outbox until {@code done} holds for the workflow (or 30 s pass). */
    protected WorkflowView drive(String type, String key, Predicate<WorkflowView> done) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        WorkflowView w = null;
        while (Instant.now().isBefore(deadline)) {
            worker.runOnce();
            drainOutbox();
            bind();
            w = engine.find(type, key).orElse(null);
            if (w != null && done.test(w)) return w;
            sleep(100);
        }
        throw new AssertionError("Workflow " + type + "/" + key + " did not reach the expected state; last = " + (w == null ? "absent" : w.status() + "@" + w.currentStep() + " err=" + w.lastError()));
    }

    protected WorkflowView driveToStep(String type, String key, String status, String step) {
        return drive(type, key, w -> status.equals(w.status()) && (step == null || step.equals(w.currentStep())));
    }

    protected WorkflowView driveToTerminal(String type, String key) { return drive(type, key, w -> WorkflowStatus.isTerminal(w.status())); }

    protected void drainOutbox() {
        int guard = 0;
        while (outbox.drainBatch() > 0 && guard++ < 50) { /* keep draining */ }
        bind();
    }

    protected static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

    // ---- fixtures -------------------------------------------------------------------------------------------------

    protected static CaptureRequest.LineInput line(String id, String sku, int qty, String price) { return new CaptureRequest.LineInput(id, sku, null, qty, Money.usd(price), null, null, null); }

    protected static CaptureRequest.CustomerInput customer(String ref) {
        return new CaptureRequest.CustomerInput(ref, "Dana Whitfield", ref + "@example.com", null, "gold", true, 34, Map.of("split_acceptance_rate", 0.2, "returns_12m", 1, "orders_12m", 4));
    }

    /** The demo.sh web order: hoodie ×2 + sneakers to Chicago, card ok, gift note (UC-01). */
    protected CaptureRequest webOrder(String channelOrderId, String token, String... notes) {
        List<CaptureRequest.NoteInput> ns = java.util.Arrays.stream(notes).map(t -> new CaptureRequest.NoteInput("customer", null, t, false)).toList();
        return new CaptureRequest("web", channelOrderId, "standard_ship", "cust-1001", customer("cust-1001"),
            List.of(line("1", "HOOD-GRY-M", 2, "68.00"), line("2", "SNK-RUN-10", 1, "145.00")),
            List.of(new CaptureRequest.AddressInput("ship_to", "Dana Whitfield", "233 S Wacker Dr", null, "Chicago", "IL", "60606", "US", null, "residential")),
            List.of(), List.of(new CaptureRequest.TenderInput("card", token, Money.usd("320.00"), Map.of())), ns, List.of(), "ground", null, "USD", Map.of(), null);
    }

    protected Order capture(CaptureRequest req) { return orders.capture(req, "test-" + req.channelOrderId()); }

    protected EntityStatus lineStatus(Order o, String lineId) { return machine.status("order_line", o.id() + ":" + lineId); }

    protected Map<String, String> lineStates(Order o) {
        Map<String, String> m = new LinkedHashMap<>();
        for (var l : o.lines()) m.put(l.lineId(), lineStatus(o, l.lineId()).state());
        return m;
    }

    protected Map<String, Object> wmsEvent(String eventId, String type, String orderId, Map<String, Object> extra) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventId", eventId);
        m.put("type", type);
        m.put("orderId", orderId);
        m.putAll(extra);
        return m;
    }

    protected String nodeOf(Order o) {
        Order fresh = orders.get(o.id());
        assertThat(fresh.fulfilmentGroups()).isNotEmpty();
        return fresh.fulfilmentGroups().stream().filter(g -> "released".equalsIgnoreCase(g.status()) || "planned".equalsIgnoreCase(g.status())).findFirst().orElse(fresh.fulfilmentGroups().get(0)).nodeId();
    }
}
