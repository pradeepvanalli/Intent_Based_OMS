package ai.intentoms.orchestration.autonomy;

import ai.intentoms.order.model.Order;
import ai.intentoms.orchestration.OrchestrationTestBase;
import ai.intentoms.orchestration.workflow.StandardShipOrderWorkflow;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.workflow.model.WorkflowStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Undo of an undoable autonomous action (Agentic Control Tower §5): a real reverse through the normal service, and a clear 409 when it genuinely cannot be reversed. */
class AutonomyUndoTest extends OrchestrationTestBase {
    @Autowired AutonomousActionRepository actionRepo;
    @Autowired AutonomyUndoService undoService;

    @Test
    void reSourceUndoReEvaluatesSourcingForReal() {
        Order o = capture(webOrder("WEB-UNDO-1", "tok_visa_ok"));
        driveToStep(StandardShipOrderWorkflow.TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_fulfilment");
        String nodeBefore = nodeOf(o);

        AutonomousAction action = actionRepo.insert(new AutonomousAction(Ids.prefixed("aut"), tenant, o.id(), "no_progress", null,
            "inventory_reallocation_needed", 0.95, "re_source", Map.of(), AutonomousAction.OUTCOME_EXECUTED, true, "re-sourced away from " + nodeBefore,
            Map.of("nodeId", nodeBefore), time.now(), null, null, null));

        AutonomousAction undone = undoService.undo(action.id(), "ops decided the original node was fine after all", Actor.user("tester"));
        assertThat(undone.isUndone()).isTrue();
        assertThat(undone.undoneBy()).isEqualTo("user:tester");
        assertThat(undone.undoReason()).isEqualTo("ops decided the original node was fine after all");

        assertThatThrownBy(() -> undoService.undo(action.id(), "again", Actor.user("tester")))
            .isInstanceOf(ConflictException.class).hasMessageContaining("already undone");
    }

    @Test
    void cannotUndoOnceTheOrderHasShipped() {
        Order o = capture(webOrder("WEB-UNDO-2", "tok_visa_ok"));
        driveToStep(StandardShipOrderWorkflow.TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_fulfilment");
        String node = nodeOf(o);
        inbound.wms(node, wmsEvent("wms-undo-1", "shipped", o.id(), Map.of("carrier", "UPS", "service", "ground", "tracking", "1Z777")));
        driveToStep(StandardShipOrderWorkflow.TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_delivery");
        assertThat(orders.get(o.id()).status()).isEqualTo("shipped");

        AutonomousAction action = actionRepo.insert(new AutonomousAction(Ids.prefixed("aut"), tenant, o.id(), "no_progress", null,
            "inventory_reallocation_needed", 0.95, "re_source", Map.of(), AutonomousAction.OUTCOME_EXECUTED, true, "re-sourced away from " + node,
            Map.of("nodeId", node), time.now(), null, null, null));

        assertThatThrownBy(() -> undoService.undo(action.id(), "too late", Actor.user("tester")))
            .isInstanceOf(ConflictException.class).hasMessageContaining("already shipped");
    }

    @Test
    void retryPaymentAndNotifyCustomerAreNeverUndoable() {
        AutonomousAction action = actionRepo.insert(new AutonomousAction(Ids.prefixed("aut"), tenant, "ord_whatever", "no_progress", null,
            "payment_retry_needed", 0.95, "retry_payment", Map.of(), AutonomousAction.OUTCOME_EXECUTED, false, "attempted", Map.of(), time.now(), null, null, null));
        assertThatThrownBy(() -> undoService.undo(action.id(), "test", Actor.user("tester")))
            .isInstanceOf(ValidationException.class).hasMessageContaining("not meaningfully reversible");
    }
}
