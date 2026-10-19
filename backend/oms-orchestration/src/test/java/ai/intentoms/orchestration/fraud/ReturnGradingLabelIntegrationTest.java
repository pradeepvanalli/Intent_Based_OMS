package ai.intentoms.orchestration.fraud;

import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.learning.learner.FraudLabels;
import ai.intentoms.orchestration.OrchestrationTestBase;
import ai.intentoms.orchestration.workflow.ReturnWorkflow;
import ai.intentoms.orchestration.workflow.StandardShipOrderWorkflow;
import ai.intentoms.order.model.Order;
import ai.intentoms.returns.model.RefundTiming;
import ai.intentoms.returns.model.ReturnChannel;
import ai.intentoms.returns.model.ReturnLineRequest;
import ai.intentoms.returns.model.ReturnRequest;
import ai.intentoms.returns.model.ReturnRma;
import ai.intentoms.returns.model.ReturnStatus;
import ai.intentoms.returns.service.ReturnService;
import ai.intentoms.workflow.model.WorkflowStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FD-06 / audit defect C1: inspecting a return labels the {@code return_risk} decision made for that RMA (subject
 * {@code return}), through the real producer: order delivered, return opened and routed, goods received and inspected
 * with {@link ReturnService}. A wrong item or an item that is not as described is confirmed return fraud; an item that
 * came back as sold is not.
 */
class ReturnGradingLabelIntegrationTest extends OrchestrationTestBase {
    @Autowired ReturnService returns;
    @Autowired DecisionRecordRepository records;

    private Order deliveredOrder(String ref) {
        Order o = capture(webOrder(ref, "tok_visa_ok"));
        driveToStep(StandardShipOrderWorkflow.TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_fulfilment");
        inbound.wms(nodeOf(o), wmsEvent(ref + "-ship", "shipped", o.id(), Map.of("carrier", "UPS", "service", "ground", "tracking", "TRK-" + ref)));
        driveToStep(StandardShipOrderWorkflow.TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_delivery");
        inbound.carrier(Map.of("eventId", ref + "-del", "type", "delivered", "orderId", o.id(), "tracking", "TRK-" + ref));
        driveToTerminal(StandardShipOrderWorkflow.TYPE, o.id());
        return orders.get(o.id());
    }

    /** Opens a return, lets the workflow decide return_risk, clears the review for refund on inspection, then receives and inspects it. */
    private ReturnRma returnedAs(String ref, String condition) {
        Order o = deliveredOrder(ref);
        ReturnRma rma = returns.create(new ReturnRequest(o.id(), List.of(new ReturnLineRequest("1", 1, null, "not what I expected")), ReturnChannel.WEB, null, null, false, null, null), SYSTEM);
        drainOutbox();
        drive(ReturnWorkflow.TYPE, rma.rmaId(), x -> WorkflowStatus.WAITING_TASK.equals(x.status()));
        tasks.complete(returns.getRma(rma.rmaId()).reviewTaskId(), RefundTiming.ON_INSPECTION, "refund once inspected", Map.of());
        drive(ReturnWorkflow.TYPE, rma.rmaId(), x -> WorkflowStatus.WAITING_SIGNAL.equals(x.status()) && ReturnStatus.AWAITING_RECEIPT.equals(returns.getRma(rma.rmaId()).status()));
        returns.receive(rma.rmaId(), "DC-NJ", SYSTEM);
        returns.inspect(rma.rmaId(), condition, "graded at the dock", SYSTEM);
        drainOutbox();
        return returns.getRma(rma.rmaId());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fraudLabelOfReturnRisk(ReturnRma rma) {
        List<DecisionRecord> risk = records.forSubject("return", rma.rmaId()).stream().filter(r -> "return_risk".equals(r.decision())).toList();
        assertThat(risk).as("the return_risk decision is recorded against the RMA").isNotEmpty();
        DecisionRecord r = records.find(risk.get(0).id()).orElseThrow();
        return r.labels() == null ? null : (Map<String, Object>) r.labels().get(FraudLabels.LABEL);
    }

    @Test
    void inspectionLabelsTheReturnRiskDecisionOfThatReturn() {
        ReturnRma wrong = returnedAs("WEB-C1A", "wrong_item");
        assertThat(fraudLabelOfReturnRisk(wrong)).containsEntry("confirmed", true).containsEntry("kind", "return_grading")
            .containsEntry("reasonCode", "wrong_item").containsEntry("provisional", false);

        ReturnRma notAsDescribed = returnedAs("WEB-C1B", "not_as_described");
        assertThat(fraudLabelOfReturnRisk(notAsDescribed)).containsEntry("confirmed", true).containsEntry("reasonCode", "not_as_described");

        ReturnRma asSold = returnedAs("WEB-C1C", "like_new");
        assertThat(fraudLabelOfReturnRisk(asSold)).containsEntry("confirmed", false).containsEntry("kind", "return_grading");
        // Order-level fraud decisions are not touched by a return's grading.
        assertThat(records.forSubject("order", asSold.orderId()).stream().filter(r -> "payment_risk".equals(r.decision())).toList())
            .allSatisfy(r -> assertThat(r.labels() == null ? null : ((Map<?, ?>) r.labels().getOrDefault(FraudLabels.LABEL, Map.of())).get("kind")).isNotEqualTo("return_grading"));
    }
}
