package ai.intentoms.workflow.model;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * What a {@link Step} sees while it runs. The context is bound to one step execution inside the instance row lock.
 */
public interface WorkflowContext {
    String workflowId();
    String businessKey();
    String tenantId();

    /** Workflow type of this instance. */
    String type();

    /** Pinned definition version (FR-WF-08). */
    int version();

    /** Name of the step being executed. */
    String step();

    /** Number of previous attempts of this step (0 on the first run). */
    int attempt();

    /** Mutable instance variables; persisted when the step completes successfully (jsonb, so keep values JSON-friendly). */
    Map<String, Object> vars();

    /**
     * Run {@code body} at most once per successful completion (FR-WF-02). The result is cached by (step, name) and
     * replayed on re-runs of the step, so bodies with side effects stay idempotent across retries, restarts and
     * deploys. Results round-trip through JSON: return Map/List/String/Number/Boolean values (or convert records with
     * {@code Json.convert}). On exception the attempt is recorded and, while {@code policy} allows, the step is
     * suspended via {@link ai.intentoms.workflow.engine.RetryLater} and re-run after the backoff; do not catch that.
     */
    <T> T activity(String name, RetryPolicy policy, Callable<T> body);

    /** {@link #activity(String, RetryPolicy, Callable)} with {@link RetryPolicy#standard()}. */
    default <T> T activity(String name, Callable<T> body) { return activity(name, RetryPolicy.standard(), body); }

    /**
     * Register a saga compensation (FR-WF-02). {@code name} is recorded durably with a snapshot of the vars; on
     * {@code Next.fail(reason, true)} the engine runs {@link WorkflowDefinition#compensations()} handlers of the
     * recorded names in reverse order. {@code undo} is an in-process fallback used only when the failure happens in
     * the same step execution and no durable handler of that name exists; it may be null.
     */
    void compensation(String name, Runnable undo);

    /** Durable-only form of {@link #compensation(String, Runnable)}. */
    default void compensation(String name) { compensation(name, null); }

    /** The signal that woke this step, if any (FR-WF-05). For task completions the name is {@code task:<taskId>}. */
    Optional<Signal> signal();

    /** Current time from the platform {@code TimeSource}. */
    Instant now();

    /** Append a timeline entry of kind {@code log} (FR-WF-06). */
    void log(String message, Map<String, Object> details);
}
