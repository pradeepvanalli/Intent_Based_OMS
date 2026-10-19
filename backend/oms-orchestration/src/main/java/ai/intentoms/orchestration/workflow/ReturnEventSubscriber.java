package ai.intentoms.orchestration.workflow;

import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.ledger.EventSubscriber;
import ai.intentoms.returns.model.ReturnEvents;
import ai.intentoms.workflow.model.WorkflowEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bridges the returns ledger to the return workflow (FR-WF-05, UC-08): {@code return.requested} starts
 * {@code return.standard} (business key = RMA id); every other {@code return.*} fact and {@code task.completed} on a return
 * subject becomes a signal (idempotency key = event id, so outbox re-deliveries are no-ops). Tasks the returns module opens
 * carry no workflow id, so their completion is forwarded here as {@code task:<taskId>}.
 */
@Component
public class ReturnEventSubscriber implements EventSubscriber {
    private static final Logger log = LoggerFactory.getLogger(ReturnEventSubscriber.class);
    private final WorkflowEngine engine;

    public ReturnEventSubscriber(WorkflowEngine engine) { this.engine = engine; }

    @Override public List<String> eventTypePrefixes() { return List.of("return.", "task.completed"); }

    @Override
    public void on(Event e) {
        if ("task.completed".equals(e.type())) { onTaskCompleted(e); return; }
        String rmaId = e.aggregateId();
        if (ReturnEvents.REQUESTED.equals(e.type())) {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("rmaId", rmaId);
            input.put("orderId", e.payload().get("orderId"));
            input.put("channel", e.payload().get("channel"));
            engine.start(ReturnWorkflow.TYPE, rmaId, input);
            return;
        }
        if (!e.type().startsWith("return.") || "return.state_initialized".equals(e.type()) || "return.transitioned".equals(e.type())) return;
        String signal = e.type().substring("return.".length());
        signal(rmaId, signal, e.payload(), e.id());
    }

    private void onTaskCompleted(Event e) {
        if (!"return".equals(e.payload().get("subjectType")) || e.payload().get("workflowId") != null) return;
        // A decision-escalation task on a return subject belongs to the decision fabric
        // (DecisionEscalationTaskSubscriber), not to the return workflow.
        if ("decision_escalation".equals(e.payload().get("type"))) return;
        String rmaId = String.valueOf(e.payload().get("subjectId"));
        String taskId = String.valueOf(e.payload().get("taskId"));
        Map<String, Object> payload = new LinkedHashMap<>(e.payload());
        payload.putIfAbsent("completedBy", e.actor().ref());
        signal(rmaId, "task:" + taskId, payload, "task:" + taskId + ":completed");
    }

    private void signal(String rmaId, String name, Map<String, Object> payload, String idempotencyKey) {
        // Look first rather than catching NotFoundException: the engine's signal is transactional, and an exception
        // thrown inside the delivery transaction marks it rollback-only even when caught here.
        if (engine.find(ReturnWorkflow.TYPE, rmaId).isEmpty()) {
            // RMA created before the workflow existed (or by a direct store write): start it, then deliver the signal.
            engine.start(ReturnWorkflow.TYPE, rmaId, Map.of("rmaId", rmaId));
            log.info("Started return workflow for {} on late event {}", rmaId, name);
        }
        engine.signal(ReturnWorkflow.TYPE, rmaId, name, payload, idempotencyKey);
    }
}
