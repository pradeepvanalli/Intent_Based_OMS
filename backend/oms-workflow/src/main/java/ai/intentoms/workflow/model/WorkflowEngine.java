package ai.intentoms.workflow.model;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Durable workflow engine (FR-WF-01, FR-WF-05, FR-WF-06, FR-WF-08). The PostgreSQL implementation lives in
 * {@code ai.intentoms.workflow.engine}; this interface is the seam for a Temporal adapter (ADR-002).
 */
public interface WorkflowEngine {
    /**
     * Start an instance of the latest version of {@code type}. Idempotent per (tenant, type, businessKey): when an
     * instance already exists its id is returned and nothing else happens.
     */
    String start(String type, String businessKey, Map<String, Object> input);

    /**
     * Deliver an external event (FR-WF-05). Signals are queued and consumed in arrival order under the instance row
     * lock; a repeated {@code idempotencyKey} for the same instance is ignored. Signals that arrive while the
     * instance is not waiting for them stay queued until a step waits for that name.
     */
    void signal(String type, String businessKey, String name, Map<String, Object> payload, String idempotencyKey);

    void signalById(String workflowId, String name, Map<String, Object> payload, String idempotencyKey);

    Optional<WorkflowView> find(String type, String businessKey);

    WorkflowView get(String workflowId);

    /** Ordered timeline incl. a synthesized pending timer and "next expected event" at the end (FR-WF-06). */
    List<TimelineEntry> timeline(String workflowId);

    /** Terminal cancel of an active instance; recorded compensations are not run. */
    void cancel(String workflowId, String reason);

    /** Ops: wake the instance now. A FAILED instance is moved back to RUNNING on its current step with attempt reset. */
    void retryNow(String workflowId);
}
