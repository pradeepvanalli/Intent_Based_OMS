package ai.intentoms.orchestration.workflow;

import ai.intentoms.finance.payment.Payment;
import ai.intentoms.finance.payment.PaymentStatus;
import ai.intentoms.inventory.model.Reservation;
import ai.intentoms.monitoring.exception.ExceptionQuery;
import ai.intentoms.monitoring.exception.ExceptionRecord;
import ai.intentoms.monitoring.exception.ExceptionService;
import ai.intentoms.orchestration.OrchestrationTestBase;
import ai.intentoms.orchestration.integration.MockWmsConnector;
import ai.intentoms.orchestration.shipment.ShipmentRecord;
import ai.intentoms.order.capture.CaptureRequest;
import ai.intentoms.order.model.FulfilmentGroup;
import ai.intentoms.order.model.Order;
import ai.intentoms.sourcing.NodeRegistry;
import ai.intentoms.workflow.model.WorkflowStatus;
import ai.intentoms.workflow.model.WorkflowView;
import ai.intentoms.workflow.task.HumanTask;
import ai.intentoms.workflow.task.TaskQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** UC-01..UC-07 end to end through the real engine, state machine, sourcing, inventory, finance and monitoring (heuristic decisions). */
class StandardShipWorkflowIntegrationTest extends OrchestrationTestBase {
    private static final String TYPE = StandardShipOrderWorkflow.TYPE;
    @Autowired MockWmsConnector wms;
    @Autowired ExceptionService exceptions;
    @Autowired NodeRegistry nodes;

    @Test
    void happyPathCaptureReleaseShipDeliver() {
        Order o = capture(webOrder("WEB-A1", "tok_visa_ok", "This is a birthday gift, please send everything together in one box."));
        WorkflowView w = driveToStep(TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_fulfilment");
        assertThat(w.vars()).containsKeys("intent", "plan", "payments");

        Order released = orders.get(o.id());
        assertThat(lineStates(released).values()).containsOnly("released");
        assertThat(released.status()).isEqualTo("released");
        assertThat(released.intent().policy()).isNotEqualTo("unclear");
        assertThat(released.fulfilmentGroups()).isNotEmpty().allSatisfy(g -> assertThat(g.status()).isEqualTo("released"));
        List<Reservation> res = reservations.forRef(o.id());
        assertThat(res).isNotEmpty().allSatisfy(r -> assertThat(r.status()).isEqualTo(Reservation.ACTIVE));
        assertThat(res.stream().mapToInt(Reservation::qty).sum()).isEqualTo(3);
        List<Payment> pays = payments.forOrder(o.id());
        assertThat(pays).hasSize(1);
        assertThat(pays.get(0).status()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(wms.releasesFor(o.id())).hasSize(released.fulfilmentGroups().size());
        assertThat(engine.timeline(w.id())).extracting(t -> t.kind()).contains("log", "activity", "step");

        // WMS ships everything from the chosen node (demo.sh step 5)
        String node = nodeOf(o);
        Map<String, Object> ack = inbound.wms(node, wmsEvent("wms-A1", "shipped", o.id(), Map.of("carrier", "UPS", "service", "ground", "tracking", "1Z999")));
        assertThat(ack).containsEntry("accepted", true).containsEntry("signal", "shipped").containsEntry("replayed", false);
        assertThat(inbound.wms(node, wmsEvent("wms-A1", "shipped", o.id(), Map.of("tracking", "1Z999")))).containsEntry("replayed", true);
        w = driveToStep(TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_delivery");
        Order shipped = orders.get(o.id());
        assertThat(lineStates(shipped).values()).containsOnly("shipped");
        assertThat(shipped.status()).isEqualTo("shipped");
        List<ShipmentRecord> shps = shipments.forOrder(o.id());
        assertThat(shps).hasSize(1);
        assertThat(shps.get(0).tracking()).isEqualTo("1Z999");
        assertThat(shps.get(0).status()).isEqualTo("in_transit");
        assertThat(payments.forOrder(o.id()).get(0).status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(reservations.forRef(o.id())).allSatisfy(r -> assertThat(r.status()).isEqualTo(Reservation.CONSUMED));
        assertThat(jdbc.sql("select count(*) from customer_communication where tenant_id = :t and order_id = :o").param("t", tenant).param("o", o.id()).query(Long.class).single()).isGreaterThanOrEqualTo(1L);

        // Carrier delivers
        Map<String, Object> del = inbound.carrier(Map.of("eventId", "car-A1", "type", "delivered", "orderId", o.id(), "tracking", "1Z999"));
        assertThat(del).containsEntry("signal", "delivered");
        w = driveToTerminal(TYPE, o.id());
        assertThat(w.status()).isEqualTo(WorkflowStatus.COMPLETED);
        Order delivered = orders.get(o.id());
        assertThat(delivered.status()).isEqualTo("delivered");
        assertThat(lineStates(delivered).values()).containsOnly("delivered");
        assertThat(shipments.forOrder(o.id()).get(0).status()).isEqualTo("delivered");
        assertThat(orders.view(o.id()).shipments()).hasSize(1);
        assertThat(orders.view(o.id()).communications()).isNotEmpty();
    }

    @Test
    void storeRejectLoopsThroughResourcing() {
        Order o = capture(webOrder("WEB-B1", "tok_visa_ok"));
        driveToStep(TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_fulfilment");
        String first = nodeOf(o);
        String sku = o.lines().get(0).sku();
        double before = nodes.penalty(first, sku);

        inbound.wms(first, wmsEvent("wms-B1", "store_rejected", o.id(), Map.of("reason", "damaged stock")));
        WorkflowView w = drive(TYPE, o.id(), x -> WorkflowStatus.WAITING_SIGNAL.equals(x.status()) && "await_fulfilment".equals(x.currentStep()) && ((Number) x.vars().get("cycle")).intValue() >= 1
            && engine.timeline(x.id()).stream().anyMatch(t -> "store_rejected#1".equals(t.name()) && "ok".equals(t.status())));
        Order re = orders.get(o.id());
        assertThat(lineStates(re).values()).as("lines re-released from the new node").containsOnly("released");
        assertThat(nodes.penalty(first, sku)).isGreaterThan(before);
        List<FulfilmentGroup> active = re.fulfilmentGroups().stream().filter(g -> "released".equals(g.status())).toList();
        assertThat(active).isNotEmpty().allSatisfy(g -> assertThat(g.nodeId()).isNotEqualTo(first));
        assertThat(re.fulfilmentGroups().stream().filter(g -> "rejected".equals(g.status())).count()).isGreaterThanOrEqualTo(1);
        assertThat(reservations.forRef(o.id()).stream().filter(Reservation::active)).allSatisfy(r -> assertThat(r.nodeId()).isNotEqualTo(first));
        List<ExceptionRecord> excs = exceptions.list(ExceptionQuery.forOrder(o.id())).items();
        assertThat(excs).anySatisfy(e -> { assertThat(e.code()).isEqualTo("store_rejected"); assertThat(e.nodeId()).isEqualTo(first); });
        assertThat(engine.timeline(w.id())).anySatisfy(t -> assertThat(t.name()).isEqualTo("store_rejected"));
    }

    @Test
    void marginFloorBreachWaitsForFinanceApproval() {
        config.set("sourcing.margin_floor", 0.6);
        Order o = capture(webOrder("WEB-C1", "tok_visa_ok"));
        WorkflowView w = driveToStep(TYPE, o.id(), WorkflowStatus.WAITING_TASK, "source");
        List<HumanTask> open = tasks.list(new TaskQuery(AbstractOrderWorkflow.MARGIN_QUEUE, "OPEN", null, "order", o.id(), null, 0, 10)).items();
        assertThat(open).hasSize(1);
        HumanTask task = open.get(0);
        assertThat(task.type()).isEqualTo(AbstractOrderWorkflow.MARGIN_TASK_TYPE);
        assertThat(task.workflowId()).isEqualTo(w.id());
        assertThat(task.payload()).containsKeys("plan", "alternatives", "marginRatio", "floor");
        assertThat(orders.get(o.id()).fulfilmentGroups()).as("nothing persisted before approval").isEmpty();
        assertThat(exceptions.list(ExceptionQuery.forOrder(o.id())).items()).anySatisfy(e -> assertThat(e.type()).isEqualTo("margin_floor"));
        assertThat(lineStates(orders.get(o.id())).values()).containsOnly("awaiting_sourcing");

        tasks.complete(task.id(), "approved", "strategic customer", Map.of());
        driveToStep(TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_fulfilment");
        Order released = orders.get(o.id());
        assertThat(released.margin().approvalPresent()).isTrue();
        assertThat(released.margin().approvalTaskId()).isEqualTo(task.id());
        assertThat(lineStates(released).values()).containsOnly("released");
        assertThat(released.fulfilmentGroups()).isNotEmpty();
        assertThat(exceptions.list(ExceptionQuery.forOrder(o.id())).items()).filteredOn(e -> "margin_floor".equals(e.type())).allSatisfy(e -> assertThat(e.status()).isEqualTo("RESOLVED"));
    }

    @Test
    void declinedPaymentFailsWithCompensation() {
        Order o = capture(webOrder("WEB-D1", "tok_visa_decline"));
        WorkflowView w = driveToTerminal(TYPE, o.id());
        assertThat(w.status()).isIn(WorkflowStatus.FAILED, WorkflowStatus.COMPENSATED);
        assertThat(w.lastError()).contains("payment_declined");
        assertThat(payments.forOrder(o.id())).allSatisfy(p -> assertThat(p.status()).isIn(PaymentStatus.FAILED, PaymentStatus.VOIDED));
        assertThat(reservations.forRef(o.id())).isEmpty();
        assertThat(exceptions.list(ExceptionQuery.forOrder(o.id())).items()).anySatisfy(e -> { assertThat(e.type()).isEqualTo("payment"); assertThat(e.code()).isEqualTo("auth_declined"); });
        assertThat(engine.timeline(w.id())).anySatisfy(t -> { assertThat(t.kind()).isEqualTo("compensation"); assertThat(t.name()).isEqualTo("void_auth"); assertThat(t.status()).isEqualTo("ok"); });
        assertThat(orders.get(o.id()).fulfilmentGroups()).isEmpty();
    }

    @Test
    void opsSignalCancelsAWaitingOrder() {
        Order o = capture(webOrder("WEB-E1", "tok_visa_ok"));
        driveToStep(TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_fulfilment");
        Map<String, Object> r = inbound.signal(o.id(), Signals.CANCEL_REQUESTED, Map.of("reason", "customer called"), "ops-1");
        assertThat(r).containsEntry("signal", "cancel_requested");
        WorkflowView w = driveToTerminal(TYPE, o.id());
        assertThat(w.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(orders.get(o.id()).status()).isEqualTo("cancelled");
        assertThat(reservations.forRef(o.id())).allSatisfy(x -> assertThat(x.status()).isEqualTo(Reservation.RELEASED));
        assertThat(payments.forOrder(o.id()).get(0).status()).isEqualTo(PaymentStatus.VOIDED);
        assertThat(wms.releasesFor(o.id())).allSatisfy(rel -> assertThat(rel.get("status")).isEqualTo("cancelled"));
    }
}
