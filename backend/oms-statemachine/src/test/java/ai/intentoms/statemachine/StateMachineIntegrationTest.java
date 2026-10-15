package ai.intentoms.statemachine;

import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.error.InvalidTransitionException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.statemachine.model.EntityStatus;
import ai.intentoms.statemachine.model.Hold;
import ai.intentoms.statemachine.model.MigrationReport;
import ai.intentoms.statemachine.model.StateModel;
import ai.intentoms.statemachine.model.TransitionDef;
import ai.intentoms.statemachine.model.TransitionRequest;
import ai.intentoms.statemachine.model.TransitionResult;
import ai.intentoms.statemachine.service.StateMachineSweeper;
import ai.intentoms.statemachine.service.StateModelLoader;
import ai.intentoms.statemachine.store.SlaTimerRepository;
import ai.intentoms.testing.PostgresTestBase;
import ai.intentoms.testing.TestApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** End-to-end behaviour of the engine against PostgreSQL: FR-SM-02, 03, 05, 06, 07, 08, 10, 11. */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class StateMachineIntegrationTest extends PostgresTestBase {
    private static final Actor SYSTEM = Actor.system("test");
    private static final List<TransitionApplied> APPLIED = new CopyOnWriteArrayList<>();
    private static final List<SlaBreachedEvent> BREACHED = new CopyOnWriteArrayList<>();
    private static volatile boolean listening;

    @Autowired StateMachine machine;
    @Autowired StateModelRegistry registry;
    @Autowired HoldService holds;
    @Autowired StateModelLoader loader;
    @Autowired EventStore events;
    @Autowired TenantConfigStore config;
    @Autowired StateMachineSweeper sweeper;
    @Autowired SlaTimerRepository timers;
    @Autowired ConfigurableApplicationContext ctx;

    @BeforeEach
    void setUp() {
        loader.loadDirectory(TestModels.DEMO_DIR);
        config.set("sourcing.margin_floor", 0.12);
        if (!listening) {
            ctx.addApplicationListener((ApplicationListener<ApplicationEvent>) e -> {
                if (e instanceof PayloadApplicationEvent<?> p) {
                    if (p.getPayload() instanceof TransitionApplied t) APPLIED.add(t);
                    if (p.getPayload() instanceof SlaBreachedEvent s) BREACHED.add(s);
                }
            });
            listening = true;
        }
    }

    private String newOrder() {
        String oid = "O" + Ids.shortId();
        machine.initialize("order", oid, "order.standard_ship", null, 1, Map.of("channel", "web"), SYSTEM);
        return oid;
    }

    private String newLine(String oid, int qty) {
        String line = oid + ":L1";
        machine.initialize("order_line", line, "order_line.standard_ship", 3, qty, Map.of("sku", "SKU-1"), SYSTEM);
        return line;
    }

    private TransitionResult fire(String type, String id, String trigger, Integer qty, String from, Map<String, Object> vars) {
        return machine.fire(new TransitionRequest(type, id, trigger, qty, from, vars, SYSTEM, "corr-" + id, null));
    }

    private static Map<String, Object> plan(double margin) { return Map.of("plan", Map.of("margin", margin, "result", "ok")); }

    // ------------------------------------------------------------------ FR-SM-02 / 05 / 11

    @Test
    void tenUnitsSplitAcrossStatesWithEventsAndHeaderRollup() {
        String oid = newOrder();
        String line = newLine(oid, 10);
        assertThat(machine.status("order_line", line).state()).isEqualTo("created");
        assertThat(machine.status("order_line", line).qtyIn("created")).isEqualTo(10);

        TransitionResult r = fire("order_line", line, "event.order_validated", null, null, Map.of());
        assertThat(r.applied()).isTrue();
        assertThat(r.fromState()).isEqualTo("created");
        assertThat(r.toState()).isEqualTo("awaiting_sourcing");
        assertThat(r.qty()).isEqualTo(10);
        assertThat(machine.status("order", oid).state()).as("header rolled up").isEqualTo("sourcing");

        r = fire("order_line", line, "decision.sourcing_plan", 7, null, plan(0.2));
        assertThat(r.applied()).isTrue();
        assertThat(r.toState()).isEqualTo("released");
        assertThat(r.actions()).containsExactly("reserve_inventory", "send_release_to_wms");
        assertThat(machine.status("order_line", line).qtyMap()).containsExactlyInAnyOrderEntriesOf(Map.of("awaiting_sourcing", 3, "released", 7));

        r = fire("order_line", line, "decision.sourcing_plan", 3, null, Map.of("plan", Map.of("result", "no_feasible_node")));
        assertThat(r.applied()).isTrue();
        assertThat(r.toState()).isEqualTo("backordered");
        assertThat(timers.open("order_line", line, "released")).hasSize(1);
        assertThat(timers.open("order_line", line, "backordered")).hasSize(1);
        assertThat(machine.status("order", oid).state()).isEqualTo("sourcing");

        assertThat(fire("order_line", line, "event.pick_started", 4, "released", Map.of()).applied()).isTrue();
        assertThat(fire("order_line", line, "event.packed", null, null, Map.of()).qty()).isEqualTo(4);
        r = fire("order_line", line, "event.shipped", null, null, Map.of());
        assertThat(r.applied()).isTrue();
        assertThat(r.fromState()).isEqualTo("packed");
        assertThat(r.toState()).isEqualTo("shipped");
        assertThat(r.qty()).isEqualTo(4);

        EntityStatus s = machine.status("order_line", line);
        assertThat(s.qtyMap()).containsExactlyInAnyOrderEntriesOf(Map.of("shipped", 4, "released", 3, "backordered", 3));
        assertThat(s.state()).isEqualTo("shipped");
        assertThat(s.modelVersion()).isEqualTo(3);
        assertThat(s.attributes()).containsEntry("sku", "SKU-1");
        assertThat(machine.status("order", oid).state()).isEqualTo("partially_shipped");
        assertThat(machine.statuses("order_line", List.of(line, "nope"))).hasSize(1);
        assertThat(timers.open("order_line", line, "released")).as("3 units still released keep the SLA timer").hasSize(1);

        // Ledger: everything on the order aggregate, FR-SM-11 payload
        List<Event> ledger = events.load("order", oid);
        assertThat(ledger).extracting(Event::type).contains("order.state_initialized", "order.transitioned");
        Event shipped = ledger.stream().filter(e -> "order.transitioned".equals(e.type()) && "event.shipped".equals(e.payload().get("trigger"))).findFirst().orElseThrow();
        assertThat(shipped.payload()).containsEntry("entityType", "order_line").containsEntry("entityId", line).containsEntry("from", "packed").containsEntry("to", "shipped")
            .containsEntry("qty", 4).containsEntry("modelId", "order_line.standard_ship").containsEntry("modelVersion", 3).containsKeys("before", "after", "guards", "actions", "transitionId");
        assertThat(shipped.actor()).isEqualTo(SYSTEM);
        assertThat(shipped.correlationId()).isEqualTo("corr-" + line);
        Event rollup = ledger.stream().filter(e -> "order.transitioned".equals(e.type()) && "rollup".equals(e.payload().get("trigger")) && "partially_shipped".equals(e.payload().get("to"))).findFirst().orElseThrow();
        assertThat(rollup.payload()).containsEntry("entityType", "order").containsEntry("entityId", oid).containsKey("childStateQty");
        assertThat(APPLIED).anyMatch(a -> a.entityId().equals(line) && a.trigger().equals("event.shipped") && a.toState().equals("shipped") && a.qty() == 4 && a.eventId().equals(shipped.id()));
        assertThat(APPLIED).anyMatch(a -> a.entityId().equals(oid) && a.trigger().equals("rollup") && a.toState().equals("partially_shipped"));

        // available transitions reflect all buckets
        List<TransitionDef> avail = machine.available("order_line", line, Map.of());
        assertThat(avail).extracting(TransitionDef::trigger).contains("event.delivered", "event.store_rejected", "event.pick_started", "event.inventory_available", "api.cancel_line");

        // cancel with qty=null drains every matching source state at once
        r = fire("order_line", line, "api.cancel_line", null, null, Map.of());
        assertThat(r.applied()).isTrue();
        assertThat(r.fromState()).isEqualTo("released,backordered");
        assertThat(r.toState()).isEqualTo("cancelled");
        assertThat(r.qty()).isEqualTo(6);
        assertThat(machine.status("order_line", line).qtyMap()).containsExactlyInAnyOrderEntriesOf(Map.of("shipped", 4, "cancelled", 6));
        assertThat(timers.open("order_line", line, null)).isEmpty();
        assertThat(machine.status("order", oid).state()).isEqualTo("shipped");

        assertThat(fire("order_line", line, "event.delivered", null, null, Map.of()).applied()).isTrue();
        s = machine.status("order_line", line);
        assertThat(s.qtyMap()).containsExactlyInAnyOrderEntriesOf(Map.of("delivered", 4, "cancelled", 6));
        assertThat(s.state()).as("all terminal -> terminal with most quantity").isEqualTo("cancelled");
        assertThat(machine.status("order", oid).state()).isEqualTo("delivered");
    }

    @Test
    void quantityRequestsAreValidated() {
        String oid = newOrder();
        String line = newLine(oid, 5);
        fire("order_line", line, "event.order_validated", null, null, Map.of());
        TransitionResult r = fire("order_line", line, "decision.sourcing_plan", 9, null, plan(0.5));
        assertThat(r.applied()).isFalse();
        assertThat(r.blockedBy()).anyMatch(b -> b.contains("insufficient quantity"));
        r = fire("order_line", line, "event.shipped", null, null, Map.of());
        assertThat(r.applied()).isFalse();
        assertThat(r.blockedBy()).anyMatch(b -> b.contains("no transition from"));
        r = fire("order_line", line, "decision.sourcing_plan", 2, "released", plan(0.5));
        assertThat(r.blockedBy()).anyMatch(b -> b.contains("no quantity in state 'released'"));
        assertThatThrownBy(() -> machine.initialize("order_line", line, "order_line.standard_ship", null, 1, Map.of(), SYSTEM)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> machine.initialize("order", "X" + Ids.shortId(), "order_line.standard_ship", null, 1, Map.of(), SYSTEM)).isInstanceOf(ValidationException.class);
    }

    // ------------------------------------------------------------------ FR-SM-03 guards

    @Test
    void marginFloorGuardBlocksUnlessApproved() {
        String oid = newOrder();
        String line = newLine(oid, 2);
        fire("order_line", line, "event.order_validated", null, null, Map.of());

        TransitionResult r = fire("order_line", line, "decision.sourcing_plan", null, null, plan(0.05));
        assertThat(r.applied()).isFalse();
        assertThat(r.blockedBy()).anyMatch(b -> b.startsWith("plan.margin >= tenant.margin_floor || approval.present"));
        assertThat(r.blockedBy()).anyMatch(b -> b.startsWith("plan.result == 'no_feasible_node'"));
        assertThat(machine.status("order_line", line).state()).isEqualTo("awaiting_sourcing");

        assertThatThrownBy(() -> fire("order_line", line, "decision.sourcing_plan", null, null, Map.of("plan", Map.of("margin", 0.05, "result", "ok"), "strict", true)))
            .isInstanceOf(InvalidTransitionException.class);

        r = fire("order_line", line, "decision.sourcing_plan", null, null, Map.of("plan", Map.of("margin", 0.05, "result", "ok"), "approval", Map.of("present", true)));
        assertThat(r.applied()).isTrue();
        assertThat(r.toState()).isEqualTo("released");

        config.set("sourcing.margin_floor", 0.30);
        String oid2 = newOrder();
        String line2 = newLine(oid2, 1);
        fire("order_line", line2, "event.order_validated", null, null, Map.of());
        assertThat(fire("order_line", line2, "decision.sourcing_plan", null, null, plan(0.2)).applied()).as("tenant root comes from tenant config").isFalse();
        config.set("sourcing.margin_floor", 0.12);
    }

    // ------------------------------------------------------------------ FR-SM-07 holds

    @Test
    void holdOnOrderBlocksLineReleaseUntilReleased() {
        String oid = newOrder();
        String line = newLine(oid, 3);
        fire("order_line", line, "event.order_validated", null, null, Map.of());

        Hold hold = holds.place("fraud", "order", oid, Set.of("released"), "velocity_check", "3 orders in 10 minutes", null, Actor.user("risk-bot"));
        assertThat(holds.blocks("order_line", line, "released")).isTrue();
        assertThat(holds.blocks("order", oid, "api.cancel_order")).isFalse();
        assertThat(holds.active("order", oid)).extracting(Hold::id).containsExactly(hold.id());

        TransitionResult r = fire("order_line", line, "decision.sourcing_plan", null, null, plan(0.4));
        assertThat(r.applied()).isFalse();
        assertThat(r.blockedBy()).contains(hold.id());
        assertThat(machine.available("order_line", line, plan(0.4))).extracting(TransitionDef::to).doesNotContain("released").contains("cancelled");

        Hold released = holds.release(hold.id(), "cleared_by_analyst", Actor.user("alice"));
        assertThat(released.resolution()).isEqualTo("cleared_by_analyst");
        assertThat(released.resolvedBy()).isEqualTo("user:alice");
        assertThat(holds.active("order", oid)).isEmpty();
        assertThatThrownBy(() -> holds.release(hold.id(), "again", null)).isInstanceOf(ConflictException.class);

        r = fire("order_line", line, "decision.sourcing_plan", null, null, plan(0.4));
        assertThat(r.applied()).isTrue();
        assertThat(r.toState()).isEqualTo("released");

        List<Event> ledger = events.load("order", oid);
        assertThat(ledger).extracting(Event::type).contains("hold.placed", "hold.released");
        Event placed = ledger.stream().filter(e -> e.type().equals("hold.placed")).findFirst().orElseThrow();
        assertThat(placed.payload()).containsEntry("holdId", hold.id()).containsEntry("type", "fraud").containsEntry("scopeType", "order").containsEntry("reasonCode", "velocity_check");
        assertThat(placed.actor()).isEqualTo(Actor.user("risk-bot"));
    }

    @Test
    void appendixGuardSeesActiveHoldsAndExpiredHoldsAreSwept() {
        String oid = newOrder();
        String line = newLine(oid, 1);
        fire("order_line", line, "event.order_validated", null, null, Map.of());
        Hold h = holds.place("payment", "order_line", line, Set.of("release"), "auth_pending", null, java.time.Duration.ofMillis(20), SYSTEM);
        TransitionResult r = fire("order_line", line, "decision.sourcing_plan", null, null, plan(0.4));
        assertThat(r.applied()).as("guard !order.holds.exists(h, 'release' in h.blocks) blocks").isFalse();
        assertThat(r.blockedBy()).contains("!order.holds.exists(h, 'release' in h.blocks)");

        sleep(40);
        assertThat(holds.active("order_line", line)).as("expired holds are not active even before the sweep").isEmpty();
        holds.releaseExpired();
        Hold swept = holds.get(h.id());
        assertThat(swept.resolution()).isEqualTo("expired");
        assertThat(swept.resolvedBy()).isEqualTo("system:hold-sweeper");
        assertThat(fire("order_line", line, "decision.sourcing_plan", null, null, plan(0.4)).applied()).isTrue();
    }

    // ------------------------------------------------------------------ FR-SM-03 permissions

    @Test
    void apiTriggersRequireTheDeclaredPermissionForUsers() {
        String oid = newOrder();
        String line = newLine(oid, 1);
        actAs("cs-rep", Set.of("order.read"));
        TransitionResult r = machine.fire(new TransitionRequest("order_line", line, "api.cancel_line", null, null, Map.of(), Actor.user("cs-rep"), null, null));
        assertThat(r.applied()).isFalse();
        assertThat(r.blockedBy()).containsExactly("permission:order.cancel");
        assertThat(machine.available("order_line", line, Map.of())).extracting(TransitionDef::trigger).doesNotContain("api.cancel_line");

        actAs("supervisor", Set.of("order.read", "order.cancel"));
        r = machine.fire(new TransitionRequest("order_line", line, "api.cancel_line", null, null, Map.of(), null, null, null));
        assertThat(r.applied()).isTrue();
        assertThat(events.load("order", oid)).filteredOn(e -> "api.cancel_line".equals(e.payload().get("trigger"))).extracting(Event::actor).containsExactly(Actor.user("supervisor"));
    }

    // ------------------------------------------------------------------ FR-SM-08 SLA timers

    @Test
    void slaTimerFiresBreachEventAndIsCancelledWhenStateIsLeft() {
        String modelId = "test.sla." + Ids.shortId();
        registry.publish("""
            stateModel: %s
            version: 1
            entity: thing
            states:
              - { id: a, meta: Created }
              - { id: b, sla: { maxAge: PT0.02S, onBreach: playbook.nudge } }
              - { id: end, meta: Terminal }
            transitions:
              - { from: a, to: b, trigger: event.go }
              - { from: b, to: end, trigger: event.finish }
            """.formatted(modelId), "sla test");
        String breached = "T" + Ids.shortId();
        String left = "T" + Ids.shortId();
        for (String id : List.of(breached, left)) {
            machine.initialize("thing", id, modelId, null, 1, Map.of(), SYSTEM);
            fire("thing", id, "event.go", null, null, Map.of());
        }
        assertThat(timers.open("thing", breached, "b")).hasSize(1);
        fire("thing", left, "event.finish", null, null, Map.of());
        assertThat(timers.open("thing", left, null)).as("leaving the state cancels its timer").isEmpty();

        sleep(60);
        sweeper.fireDueSlaTimers();

        List<Event> ledger = events.load("thing", breached);
        Event e = ledger.stream().filter(ev -> ev.type().equals("sla.breached")).findFirst().orElseThrow();
        assertThat(e.payload()).containsEntry("entityType", "thing").containsEntry("entityId", breached).containsEntry("state", "b").containsEntry("onBreach", "playbook.nudge").containsKey("ageSeconds");
        assertThat(e.actor()).isEqualTo(Actor.system("sla-sweeper"));
        assertThat(timers.open("thing", breached, null)).isEmpty();
        assertThat(timers.history("thing", breached)).singleElement().satisfies(t -> assertThat(t.firedAt()).isNotNull());
        assertThat(BREACHED).anyMatch(b -> b.entityId().equals(breached) && b.state().equals("b") && b.onBreach().equals("playbook.nudge") && b.tenant().equals(TENANT) && b.eventId().equals(e.id()));
        assertThat(events.load("thing", left)).extracting(Event::type).doesNotContain("sla.breached");
        assertThat(sweeper.fireDueSlaTimers()).as("a fired timer never fires twice").isEqualTo(0);
    }

    // ------------------------------------------------------------------ FR-SM-06 versioning & migration

    @Test
    void migrationDryRunReportsThenApplyMovesMappableEntities() {
        String modelId = "test.mig." + Ids.shortId();
        registry.publish(TestModels.simple(modelId, 1), "v1");
        registry.publish(TestModels.simple(modelId, 2).replace("- { id: b }", "- { id: c }").replace("to: b,", "to: c,").replace("from: b,", "from: c,").replace("[a, b]", "[a, c]"), "v2 renames b to c");
        assertThat(registry.latest(modelId).version()).isEqualTo(2);
        assertThat(registry.versions(modelId)).extracting(StateModel::version).containsExactly(1, 2);

        String stays = "E" + Ids.shortId();
        String moves = "E" + Ids.shortId();
        machine.initialize("thing", stays, modelId, 1, 4, Map.of(), SYSTEM);
        machine.initialize("thing", moves, modelId, 1, 4, Map.of(), SYSTEM);
        fire("thing", stays, "event.go", 2, null, Map.of()); // 2 in a, 2 in b -> b does not exist in v2

        MigrationReport dry = registry.migrate(modelId, 1, 2, true);
        assertThat(dry.dryRun()).isTrue();
        assertThat(dry.total()).isEqualTo(2);
        assertThat(dry.migrated()).isEqualTo(1);
        assertThat(dry.skipped()).isEqualTo(1);
        assertThat(dry.perEntity()).filteredOn(p -> p.entityId().equals(stays)).singleElement().satisfies(p -> {
            assertThat(p.status()).isEqualTo("skipped");
            assertThat(p.reason()).contains("unmapped: b");
            assertThat(p.states()).containsExactlyInAnyOrderEntriesOf(Map.of("a", 2, "b", 2));
        });
        assertThat(machine.status("thing", moves).modelVersion()).as("dry run changes nothing").isEqualTo(1);
        assertThat(events.load("thing", moves)).extracting(Event::type).doesNotContain("statemodel.migrated");

        MigrationReport real = registry.migrate(modelId, 1, 2, false);
        assertThat(real.migrated()).isEqualTo(1);
        assertThat(machine.status("thing", moves).modelVersion()).isEqualTo(2);
        assertThat(machine.status("thing", stays).modelVersion()).isEqualTo(1);
        assertThat(events.load("thing", moves)).filteredOn(e -> e.type().equals("statemodel.migrated")).singleElement()
            .satisfies(e -> assertThat(e.payload()).containsEntry("fromVersion", 1).containsEntry("toVersion", 2).containsEntry("modelId", modelId));
        // the migrated entity now follows v2 transitions
        assertThat(fire("thing", moves, "event.go", null, null, Map.of()).toState()).isEqualTo("c");
        assertThat(registry.migrate(modelId, 1, 2, true).total()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ FR-SM-09 / FR-SM-10 registry

    @Test
    void publishEnforcesStaticChecksReasonAndUniqueness() {
        String modelId = "test.pub." + Ids.shortId();
        assertThatThrownBy(() -> registry.publish(TestModels.simple(modelId, 1), " ")).isInstanceOf(ValidationException.class).hasMessageContaining("reason");
        assertThatThrownBy(() -> registry.publish(TestModels.simple(modelId, 1).replace("permission: order.cancel", "note: none"), "x"))
            .isInstanceOf(ValidationException.class)
            .satisfies(ex -> assertThat(((ValidationException) ex).details()).containsKey("issues"));
        assertThatThrownBy(() -> registry.publish("stateModel: x\nstates:\n  - { id: a, sla: { maxAge: nope } }", "x")).isInstanceOf(ValidationException.class).hasMessageContaining("parsed");

        StateModel m = registry.publish(TestModels.simple(modelId, 1), "initial");
        assertThat(m.version()).isEqualTo(1);
        assertThatThrownBy(() -> registry.publish(TestModels.simple(modelId, 1), "again")).isInstanceOf(ConflictException.class);
        assertThat(registry.get(modelId, 1).yaml()).isEqualTo(TestModels.simple(modelId, 1));
        assertThat(registry.modelIds()).contains(modelId, "order_line.standard_ship", "order.standard_ship", "order_line.bopis", "order.bopis", "return.standard", "shipment.standard");
        assertThat(registry.validate(TestModels.demo("order_line.standard_ship.yaml")).ok()).isTrue();

        Long audits = jdbc.sql("select count(*) from audit_log where tenant_id = :t and action = 'statemodel.publish' and object_id = :o")
            .param("t", TENANT).param("o", modelId + "@1").query(Long.class).single();
        assertThat(audits).isEqualTo(1);
        assertThat(events.load("config", "statemodel:" + modelId)).extracting(Event::type).containsExactly("statemodel.published");

        assertThat(loader.loadDirectory(TestModels.DEMO_DIR)).as("loader is idempotent").isEmpty();
        assertThat(registry.latest("order_line.standard_ship").version()).isEqualTo(3);
    }

    @Test
    void rollupApiDerivesHeaderStateFromLineQuantities() {
        assertThat(machine.rollup("order.standard_ship", 1, Map.of("shipped", 4, "released", 3, "backordered", 3))).isEqualTo("partially_shipped");
        assertThat(machine.rollup("order.standard_ship", 1, Map.of("delivered", 10))).isEqualTo("delivered");
        assertThat(machine.rollup("order.bopis", 1, Map.of("ready_for_pickup", 2, "picked", 1))).isEqualTo("partially_ready");
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
