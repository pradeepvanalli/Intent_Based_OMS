package ai.intentoms.workflow.engine;

import ai.intentoms.workflow.model.WorkflowView;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Full persisted state of a workflow instance (one {@code workflow_instance} row). Internal to the engine. */
public record WorkflowInstance(
    String id, String tenantId, String type, int version, String businessKey, String status, String currentStep,
    Map<String, Object> vars, Instant startedAt, Instant updatedAt, Instant nextWakeAt, Instant timeoutAt,
    Set<String> waitSignals, String waitTaskId, String onTimeoutStep, String onSignalStep, String onTaskStep,
    String lastError, int attempt, List<Map<String, Object>> compensations, Instant completedAt,
    Map<String, Object> result, String cancelReason
) {
    public WorkflowView toView() {
        return new WorkflowView(id, type, version, businessKey, status, currentStep, vars, startedAt, updatedAt, nextWakeAt, lastError);
    }
}
