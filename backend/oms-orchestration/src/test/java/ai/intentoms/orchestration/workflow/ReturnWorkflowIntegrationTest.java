package ai.intentoms.orchestration.workflow;

import ai.intentoms.finance.payment.Refund;
import ai.intentoms.finance.payment.RefundStatus;
import ai.intentoms.orchestration.OrchestrationTestBase;
import ai.intentoms.order.model.Order;
import ai.intentoms.returns.model.RefundTiming;
import ai.intentoms.returns.model.ReturnChannel;
import ai.intentoms.returns.model.ReturnLineRequest;
import ai.intentoms.returns.model.ReturnRequest;
import ai.intentoms.returns.model.ReturnRma;
import ai.intentoms.returns.model.ReturnStatus;
import ai.intentoms.returns.model.ReturnTasks;
import ai.intentoms.returns.service.ReturnService;
import ai.intentoms.workflow.model.WorkflowStatus;
import ai.intentoms.workflow.model.WorkflowView;
import ai.intentoms.workflow.task.HumanTask;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** UC-08: delivered order → RMA → return workflow (review task, carrier scan, receipt, inspection, refund). */
class ReturnWorkflowIntegrationTest extends OrchestrationTestBase {
    @Autowired ReturnService returns;

    private Order deliveredOrder(String ref) {
        Order o = capture(webOrder(ref, "tok_visa_ok"));
        driveToStep(StandardShipOrderWorkflow.TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_fulfilment");
        inbound.wms(nodeOf(o), wmsEvent(ref + "-ship", "shipped", o.id(), Map.of("carrier", "UPS", "service", "ground", "tracking", "TRK-" + ref)));
        driveToStep(StandardShipOrderWorkflow.TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_delivery");
        inbound.carrier(Map.of("eventId", ref + "-del", "type", "delivered", "orderId", o.id(), "tracking", "TRK-" + ref));
        driveToTerminal(StandardShipOrderWorkflow.TYPE, o.id());
        assertThat(orders.get(o.id()).status()).isEqualTo("delivered");
        return orders.get(o.id());
    }

    @Test
    void returnIsReviewedScannedReceivedInspectedAndRefunded() {
        Order o = deliveredOrder("WEB-R1");
        // receiptPresent=false keeps the case out of the trusted-customer fast lane (return_risk v2 L0 rule) so the model is consulted.
        ReturnRma rma = returns.create(new ReturnRequest(o.id(), List.of(new ReturnLineRequest("1", 1, null, "too small")), ReturnChannel.WEB, null, null, false, null, null), SYSTEM);
        drainOutbox();   // return.requested → workflow start
        WorkflowView w = drive(ReturnWorkflow.TYPE, rma.rmaId(), x -> WorkflowStatus.WAITING_TASK.equals(x.status()));
        assertThat(w.currentStep()).isEqualTo("route");
        ReturnRma decided = returns.getRma(rma.rmaId());
        assertThat(decided.reviewTaskType()).as("heuristic provider never reaches the T2 act band → review").isEqualTo(ReturnTasks.TYPE_RETURN_REVIEW);
        assertThat(decided.refundTiming()).isEqualTo(RefundTiming.ESCALATE);
        HumanTask review = tasks.get(decided.reviewTaskId());
        assertThat(review.queue()).isEqualTo(ReturnTasks.QUEUE_RISK_REVIEW);
        assertThat(review.workflowId()).isNull();

        tasks.complete(review.id(), RefundTiming.ON_INSPECTION, "looks fine, refund once inspected", Map.of());
        w = drive(ReturnWorkflow.TYPE, rma.rmaId(), x -> WorkflowStatus.WAITING_SIGNAL.equals(x.status()) && ReturnStatus.AWAITING_RECEIPT.equals(returns.getRma(rma.rmaId()).status()));
        assertThat(returns.getRma(rma.rmaId()).refundTiming()).isEqualTo(RefundTiming.ON_INSPECTION);

        inbound.carrier(Map.of("eventId", "ret-scan-1", "type", "return_scan", "rmaId", rma.rmaId(), "tracking", "RET-1"));
        assertThat(returns.getRma(rma.rmaId()).scannedAt()).isNotNull();
        assertThat(returns.getRma(rma.rmaId()).refunded()).as("on_inspection does not refund at scan").isFalse();
        drive(ReturnWorkflow.TYPE, rma.rmaId(), x -> WorkflowStatus.WAITING_SIGNAL.equals(x.status()));

        inbound.wms("DC-NJ", Map.of("eventId", "ret-recv-1", "type", "received", "rmaId", rma.rmaId()));
        assertThat(returns.getRma(rma.rmaId()).status()).isEqualTo(ReturnStatus.RECEIVED);
        drive(ReturnWorkflow.TYPE, rma.rmaId(), x -> WorkflowStatus.WAITING_SIGNAL.equals(x.status()) && engine.timeline(x.id()).stream().anyMatch(t -> "received".equals(t.name()) && "consumed".equals(t.status())));

        inbound.wms("DC-NJ", Map.of("eventId", "ret-insp-1", "type", "inspected", "rmaId", rma.rmaId(), "condition", "like_new", "note", "unworn"));
        w = driveToTerminal(ReturnWorkflow.TYPE, rma.rmaId());
        assertThat(w.status()).isEqualTo(WorkflowStatus.COMPLETED);
        ReturnRma done = returns.getRma(rma.rmaId());
        assertThat(done.status()).isEqualTo(ReturnStatus.REFUNDED);
        assertThat(done.refunded()).isTrue();
        assertThat(done.disposition()).isNotNull();
        List<Refund> refunds = payments.refundsForOrder(o.id());
        assertThat(refunds).hasSize(1);
        assertThat(refunds.get(0).returnId()).isEqualTo(rma.rmaId());
        assertThat(refunds.get(0).status()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(w.vars()).containsEntry("orderId", o.id());
    }
}
