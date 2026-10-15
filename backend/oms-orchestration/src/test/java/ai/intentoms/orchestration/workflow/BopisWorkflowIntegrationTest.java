package ai.intentoms.orchestration.workflow;

import ai.intentoms.finance.model.Money;
import ai.intentoms.finance.payment.PaymentStatus;
import ai.intentoms.inventory.model.Reservation;
import ai.intentoms.orchestration.OrchestrationTestBase;
import ai.intentoms.order.capture.CaptureRequest;
import ai.intentoms.order.model.Order;
import ai.intentoms.workflow.model.WorkflowStatus;
import ai.intentoms.workflow.model.WorkflowView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** UC-04 pickup: BOPIS order sourced to the Chicago store, staged, picked up and captured. */
class BopisWorkflowIntegrationTest extends OrchestrationTestBase {

    private CaptureRequest bopis(String channelOrderId) {
        return new CaptureRequest("web", channelOrderId, "bopis", "cust-1019", customer("cust-1019"), List.of(line("1", "HOOD-GRY-M", 1, "68.00")),
            List.of(new CaptureRequest.AddressInput("pickup", "Evelyn", "ST-CHI-01", null, "Chicago", "IL", "60611", "US", null, "commercial"),
                new CaptureRequest.AddressInput("ship_to", "Evelyn", "Store pickup", null, "Chicago", "IL", "60611", "US", null, "commercial")),
            List.of(), List.of(new CaptureRequest.TenderInput("card", "tok_visa_ok", Money.usd("80.00"), Map.of())), List.of(), List.of("pickup_consolidated"), "pickup", null, "USD", Map.of(), null);
    }

    @Test
    void bopisOrderIsPickedUpAndCaptured() {
        Order o = capture(bopis("WEB-BOPIS-1"));
        WorkflowView w = driveToStep(BopisOrderWorkflow.TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_pick");
        Order released = orders.get(o.id());
        assertThat(lineStates(released).values()).containsOnly("released");
        String store = nodeOf(o);
        assertThat(store).startsWith("ST-");
        assertThat(reservations.forRef(o.id())).hasSize(1).allSatisfy(r -> assertThat(r.nodeId()).isEqualTo(store));

        inbound.wms(store, wmsEvent("bopis-1-staged", "staged_for_pickup", o.id(), Map.of()));
        driveToStep(BopisOrderWorkflow.TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_pickup");
        assertThat(lineStates(orders.get(o.id())).values()).containsOnly("ready_for_pickup");
        assertThat(orders.get(o.id()).status()).isEqualTo("ready_for_pickup");

        inbound.wms(store, wmsEvent("bopis-1-pickedup", "picked_up", o.id(), Map.of()));
        w = driveToTerminal(BopisOrderWorkflow.TYPE, o.id());
        assertThat(w.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(lineStates(orders.get(o.id())).values()).containsOnly("picked_up");
        assertThat(orders.get(o.id()).status()).isEqualTo("picked_up");
        assertThat(payments.forOrder(o.id()).get(0).status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(reservations.forRef(o.id())).allSatisfy(r -> assertThat(r.status()).isEqualTo(Reservation.CONSUMED));
    }

    @Test
    void pickupWindowExpiryCancelsAndVoids() {
        config.set("orchestration.pickup_window", ai.intentoms.platform.json.Json.write("PT1S"));
        Order o = capture(bopis("WEB-BOPIS-2"));
        driveToStep(BopisOrderWorkflow.TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_pick");
        inbound.wms(nodeOf(o), wmsEvent("bopis-2-staged", "staged_for_pickup", o.id(), Map.of()));
        driveToStep(BopisOrderWorkflow.TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_pickup");
        sleep(1200);
        WorkflowView w = driveToTerminal(BopisOrderWorkflow.TYPE, o.id());
        assertThat(w.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(orders.get(o.id()).status()).isEqualTo("cancelled");
        assertThat(payments.forOrder(o.id()).get(0).status()).isEqualTo(PaymentStatus.VOIDED);
        assertThat(reservations.forRef(o.id())).allSatisfy(r -> assertThat(r.status()).isEqualTo(Reservation.RELEASED));
    }
}
