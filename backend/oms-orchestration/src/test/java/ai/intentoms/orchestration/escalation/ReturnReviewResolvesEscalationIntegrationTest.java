package ai.intentoms.orchestration.escalation;

import ai.intentoms.decision.model.DecisionEscalation;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.Layer;
import ai.intentoms.decision.record.DecisionEscalationRepository;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.learning.model.HumanResolution;
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
import ai.intentoms.workflow.task.HumanTask;
import ai.intentoms.workflow.task.TaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OC-06 (audit C-N5): a risky return escalates its return_risk decision (a {@code decision_escalation} task) and opens a
 * {@code return_review} task. Answering the review, on the Tasks page (the return workflow then applies it) or through
 * {@code POST /returns/{rma}/review}, is the answer to that decision: its escalation is resolved, its escalation task is
 * closed by the same person, and the record is labelled {@code human_resolution} with the value in the decision's own
 * terms, the outcome, the reason, the recommendation and who resolved it. Before, the escalation stayed OPEN and the
 * record unlabelled after the return was received, inspected and refunded.
 */
class ReturnReviewResolvesEscalationIntegrationTest extends OrchestrationTestBase {
    @Autowired ReturnService returns;
    @Autowired DecisionRecordRepository records;
    @Autowired DecisionEscalationRepository escalations;

    private Order deliveredOrder(String ref) {
        Order o = capture(webOrder(ref, "tok_visa_ok"));
        driveToStep(StandardShipOrderWorkflow.TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_fulfilment");
        inbound.wms(nodeOf(o), wmsEvent(ref + "-ship", "shipped", o.id(), Map.of("carrier", "UPS", "service", "ground", "tracking", "TRK-" + ref)));
        driveToStep(StandardShipOrderWorkflow.TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_delivery");
        inbound.carrier(Map.of("eventId", ref + "-del", "type", "delivered", "orderId", o.id(), "tracking", "TRK-" + ref));
        driveToTerminal(StandardShipOrderWorkflow.TYPE, o.id());
        return orders.get(o.id());
    }

    /** A return whose return_risk decision escalated to a person and is waiting on its review task. */
    private ReturnRma escalatedReturn(String ref) {
        Order o = deliveredOrder(ref);
        ReturnRma rma = returns.create(new ReturnRequest(o.id(), List.of(new ReturnLineRequest("1", 1, null, "not what I expected")), ReturnChannel.WEB, null, null, false, null, null), SYSTEM);
        drainOutbox();
        drive(ReturnWorkflow.TYPE, rma.rmaId(), x -> WorkflowStatus.WAITING_TASK.equals(x.status()));
        ReturnRma waiting = returns.getRma(rma.rmaId());
        assertThat(waiting.refundTiming()).isEqualTo(RefundTiming.ESCALATE);
        DecisionRecord risk = records.find(waiting.riskDecisionRecordId()).orElseThrow();
        assertThat(risk.layer()).isEqualTo(Layer.L3_HUMAN);
        assertThat(risk.escalationId()).as("the decision itself escalated").isNotNull();
        assertThat(escalations.find(risk.escalationId()).orElseThrow().status()).isEqualTo("OPEN");
        return waiting;
    }

    private void assertResolvedAndLabelled(ReturnRma rma, String expectedOutcome) {
        DecisionRecord risk = records.find(rma.riskDecisionRecordId()).orElseThrow();
        DecisionEscalation esc = escalations.find(risk.escalationId()).orElseThrow();
        assertThat(esc.status()).isEqualTo("RESOLVED");
        assertThat(esc.resolvedBy()).isEqualTo("user:tester");
        HumanTask escTask = tasks.get(esc.taskId());
        assertThat(escTask.status()).as("the decision's escalation task is closed").isEqualTo(TaskStatus.COMPLETED);
        Map<String, Object> label = HumanResolution.label(risk).orElseThrow();
        assertThat(label).containsEntry("outcome", expectedOutcome).containsEntry("reasonCode", "refund once inspected")
            .containsEntry("resolvedBy", "user:tester").containsEntry("resolvedByType", "person").containsEntry("recommendedValue", String.valueOf(risk.value()));
        assertThat(HumanResolution.value(risk)).as("the answer in return_risk's own terms, readable by the learners").isPresent();
        double model = ((Number) risk.value()).doubleValue();
        if ("accept".equals(expectedOutcome)) assertThat(HumanResolution.agreedWithModel(risk)).contains(true);
        else assertThat(Double.parseDouble(HumanResolution.value(risk).orElseThrow())).isEqualTo(2.0).isNotEqualTo(model);
    }

    @Test
    void answeringTheReviewOnTheTasksPageResolvesAndLabelsTheRiskEscalation() {
        ReturnRma rma = escalatedReturn("WEB-CN5A");
        double model = ((Number) records.find(rma.riskDecisionRecordId()).orElseThrow().value()).doubleValue();
        // The person answers the review task; the return workflow applies it as the system.
        tasks.complete(rma.reviewTaskId(), RefundTiming.ON_INSPECTION, "refund once inspected", Map.of());
        drive(ReturnWorkflow.TYPE, rma.rmaId(), x -> WorkflowStatus.WAITING_SIGNAL.equals(x.status()) && ReturnStatus.AWAITING_RECEIPT.equals(returns.getRma(rma.rmaId()).status()));
        drainOutbox();
        assertResolvedAndLabelled(rma, model >= 2.0 && model < 3.0 ? "accept" : "override");
    }

    @Test
    void answeringTheReviewThroughTheReturnsApiResolvesAndLabelsTheRiskEscalation() {
        ReturnRma rma = escalatedReturn("WEB-CN5B");
        double model = ((Number) records.find(rma.riskDecisionRecordId()).orElseThrow().value()).doubleValue();
        returns.applyReview(rma.rmaId(), RefundTiming.ON_INSPECTION, "refund once inspected", ai.intentoms.platform.ledger.Actor.user("tester"));
        drainOutbox();
        assertResolvedAndLabelled(rma, model >= 2.0 && model < 3.0 ? "accept" : "override");
        assertThat(escalations.open("return_risk")).noneMatch(e -> rma.riskDecisionRecordId().equals(e.recordId()));
    }
}
