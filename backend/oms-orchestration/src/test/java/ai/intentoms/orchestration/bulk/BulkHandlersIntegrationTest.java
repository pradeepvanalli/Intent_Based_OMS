package ai.intentoms.orchestration.bulk;

import ai.intentoms.orchestration.OrchestrationTestBase;
import ai.intentoms.orchestration.workflow.StandardShipOrderWorkflow;
import ai.intentoms.order.model.Order;
import ai.intentoms.statemachine.HoldService;
import ai.intentoms.statemachine.model.Hold;
import ai.intentoms.workflow.bulk.BulkOperationService;
import ai.intentoms.workflow.model.WorkflowStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-WF-07: bulk hold dry-run vs apply, rollback, release signal and registration of every handler. */
class BulkHandlersIntegrationTest extends OrchestrationTestBase {
    @Autowired HoldBulkHandler hold;
    @Autowired ReleaseBulkHandler release;
    @Autowired CancelBulkHandler cancel;
    @Autowired HoldService holds;
    @Autowired BulkOperationService bulk;

    @Test
    void holdDryRunChangesNothingAndApplyPlacesAReversibleHold() {
        Order o = capture(webOrder("WEB-H1", "tok_visa_ok"));
        driveToStep(StandardShipOrderWorkflow.TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_fulfilment");
        Map<String, Object> params = Map.of("type", "fraud", "blocks", List.of("release", "event.shipped"), "reason", "storm review", "ttl", "PT2H");

        Map<String, Object> dry = hold.apply(o.id(), params, true);
        assertThat(dry).containsEntry("wouldPlace", true).containsEntry("type", "fraud");
        assertThat(holds.active("order", o.id())).isEmpty();

        Map<String, Object> applied = hold.apply(o.id(), params, false);
        List<Hold> active = holds.active("order", o.id());
        assertThat(active).hasSize(1);
        assertThat(active.get(0).type()).isEqualTo("fraud");
        assertThat(active.get(0).blocksTransitions()).contains("release", "event.shipped");
        assertThat(applied).containsEntry("holdId", active.get(0).id());
        assertThat(hold.apply(o.id(), params, false)).containsKey("skipped");

        Map<String, Object> relDry = release.apply(o.id(), Map.of("type", "fraud"), true);
        assertThat(relDry).containsEntry("wouldRelease", 1);
        assertThat(holds.active("order", o.id())).hasSize(1);
        Map<String, Object> rel = release.apply(o.id(), Map.of("type", "fraud"), false);
        assertThat(rel).containsEntry("signalled", true);
        assertThat(holds.active("order", o.id())).isEmpty();

        Map<String, Object> again = hold.apply(o.id(), params, false);
        assertThat(hold.rollback(o.id(), again)).isPresent();
        assertThat(holds.active("order", o.id())).isEmpty();
    }

    @Test
    void cancelDryRunReportsFeasibilityAndEveryHandlerIsRegistered() {
        Order o = capture(webOrder("WEB-H2", "tok_visa_ok"));
        driveToStep(StandardShipOrderWorkflow.TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_fulfilment");
        Map<String, Object> dry = cancel.apply(o.id(), Map.of("reason", "test"), true);
        assertThat(dry).containsEntry("wouldCancel", true);
        assertThat(orders.get(o.id()).status()).isEqualTo("released");
        assertThat(bulk.actions()).contains("resource", "repromise", "hold", "release", "cancel");
    }
}
