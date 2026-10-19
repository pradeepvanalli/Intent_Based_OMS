package ai.intentoms.orchestration.workflow;

import ai.intentoms.inventory.model.ReservationLine;
import ai.intentoms.inventory.service.InventoryService;
import ai.intentoms.orchestration.OrchestrationTestBase;
import ai.intentoms.order.model.FulfilmentGroup;
import ai.intentoms.order.model.Order;
import ai.intentoms.platform.json.Json;
import ai.intentoms.workflow.engine.StepRunner;
import ai.intentoms.workflow.model.WorkflowStatus;
import ai.intentoms.workflow.model.WorkflowView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit A1: two orders planned against the same last units both reach the reserve step; the one that reserves second
 * finds the stock gone. It must re-source (or backorder), never fail the step and compensate the order.
 */
class ReservationRaceIntegrationTest extends OrchestrationTestBase {
    private static final String TYPE = StandardShipOrderWorkflow.TYPE;
    private static final String HOODIE = "HOOD-GRY-M";
    @Autowired StepRunner runner;
    @Autowired InventoryService inventory;

    private List<Map<String, Object>> events(String orderId, String type) {
        return jdbc.sql("select payload::text as p from event where tenant_id = :t and aggregate_id = :o and type = :ty order by position").param("t", tenant).param("o", orderId).param("ty", type)
            .query((rs, i) -> Json.readMap(rs.getString("p"))).list();
    }

    /** Takes the workflow out of the due queue so the runner advances only the other one. */
    private void park(Order o) {
        jdbc.sql("update workflow_instance set next_wake_at = null where tenant_id = :t and type = :ty and business_key = :k").param("t", tenant).param("ty", TYPE).param("k", o.id()).update();
    }

    private void unpark(Order o) {
        jdbc.sql("update workflow_instance set next_wake_at = :now where tenant_id = :t and type = :ty and business_key = :k").param("now", Timestamp.from(time.now())).param("t", tenant).param("ty", TYPE).param("k", o.id()).update();
    }

    /** One step at a time, so the workflow can be stopped exactly before a step runs. */
    private WorkflowView stepUntil(Order o, Predicate<WorkflowView> done) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        WorkflowView w = null;
        while (Instant.now().isBefore(deadline)) {
            w = engine.find(TYPE, o.id()).orElse(null);
            if (w != null && done.test(w)) return w;
            boolean ran = runner.runNext();
            drainOutbox();
            if (!ran) sleep(20);
        }
        throw new AssertionError("Workflow " + o.id() + " did not reach the expected state; last = " + (w == null ? "absent" : w.status() + "@" + w.currentStep() + " err=" + w.lastError()));
    }

    private static boolean atReserve(WorkflowView w) { return WorkflowStatus.RUNNING.equals(w.status()) && "reserve".equals(w.currentStep()); }

    private String hoodieNode(Order o) {
        return orders.get(o.id()).fulfilmentGroups().stream().filter(g -> !"rejected".equalsIgnoreCase(g.status()) && !"cancelled".equalsIgnoreCase(g.status()))
            .filter(g -> g.allocations().stream().anyMatch(a -> HOODIE.equals(a.sku()))).map(FulfilmentGroup::nodeId).findFirst().orElseThrow();
    }

    @Test
    void theOrderThatLosesTheLastUnitsReSourcesInsteadOfBeingCompensated() {
        Order a = capture(webOrder("RACE-A", "tok_visa_ok"));
        Order b = capture(webOrder("RACE-B", "tok_visa_ok"));
        drainOutbox();

        // Both orders are planned against the same stock before either reserves.
        park(b);
        stepUntil(a, ReservationRaceIntegrationTest::atReserve);
        park(a);
        unpark(b);
        stepUntil(b, ReservationRaceIntegrationTest::atReserve);
        String node = hoodieNode(a);
        assertThat(hoodieNode(b)).isEqualTo(node);

        // Only two hoodies are left there: the last units, enough for exactly one of the two orders.
        int available = inventory.atp(HOODIE, node).available();
        assertThat(available).isGreaterThanOrEqualTo(2);
        if (available > 2) reservations.reserveAll(List.of(new ReservationLine(HOODIE, node, available - 2)), "cart", "other-carts-" + tenant, Duration.ofHours(1));

        // A wins the race.
        park(b);
        unpark(a);
        WorkflowView wa = stepUntil(a, w -> !atReserve(w) && !"release".equals(w.currentStep()) && !WorkflowStatus.RUNNING.equals(w.status()));
        assertThat(wa.status()).isEqualTo(WorkflowStatus.WAITING_SIGNAL);
        assertThat(wa.currentStep()).isEqualTo("await_fulfilment");
        assertThat(inventory.atp(HOODIE, node).available()).isZero();

        // B loses: the shortage is a result of the reserve step, so B re-sources away from that node (or backorders).
        unpark(b);
        WorkflowView wb = stepUntil(b, w -> WorkflowStatus.isTerminal(w.status()) || (!WorkflowStatus.RUNNING.equals(w.status()) && !WorkflowStatus.COMPENSATING.equals(w.status())));
        assertThat(wb.status()).isNotIn(WorkflowStatus.COMPENSATED, WorkflowStatus.FAILED, WorkflowStatus.COMPENSATING, WorkflowStatus.CANCELLED);
        assertThat(wb.lastError()).isNull();
        assertThat(wb.currentStep()).isIn("await_fulfilment", "backorder");
        assertThat(events(b.id(), "order.resourced")).singleElement().satisfies(e -> {
            assertThat(e).containsEntry("reason", "insufficient_atp");
            assertThat(String.valueOf(e.get("rejectedNodes"))).isEqualTo("[" + node + "]");
        });
        if ("await_fulfilment".equals(wb.currentStep())) assertThat(hoodieNode(b)).isNotEqualTo(node);
        assertThat(lineStates(orders.get(b.id())).values()).doesNotContain("cancelled");
        assertThat(lineStates(orders.get(a.id())).values()).doesNotContain("cancelled", "backordered");
    }
}
