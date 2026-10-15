package ai.intentoms.workflow.model;

import java.time.Instant;
import java.util.Map;

/**
 * Read model of a workflow instance. Status is one of RUNNING, WAITING_SIGNAL, WAITING_TIMER, WAITING_TASK,
 * COMPENSATING, COMPLETED, FAILED, COMPENSATED, CANCELLED.
 */
public record WorkflowView(String id, String type, int version, String businessKey, String status, String currentStep,
                           Map<String, Object> vars, Instant startedAt, Instant updatedAt, Instant nextWakeAt, String lastError) {}
