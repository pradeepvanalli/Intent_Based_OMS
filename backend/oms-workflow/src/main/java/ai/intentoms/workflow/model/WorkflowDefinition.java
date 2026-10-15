package ai.intentoms.workflow.model;

import java.time.Duration;
import java.util.Map;

/**
 * A workflow type: a named set of steps plus the step an instance starts in. Definitions are Spring beans; the
 * {@link ai.intentoms.workflow.engine.WorkflowRegistry} indexes them by (type, version). New instances start on the
 * highest registered version and stay pinned to it for life (FR-WF-08), so keep older definition beans registered
 * while instances created on them are still in flight.
 *
 * <p>Steps must be deterministic given {@link WorkflowContext#vars()} and the cached activity results: a step may be
 * re-run after a retry, a restart or a deploy, and only {@link WorkflowContext#activity} bodies are guaranteed to
 * execute at most once per successful completion (FR-WF-01, FR-WF-02).
 */
public interface WorkflowDefinition {
    /** Workflow type, e.g. {@code order.standard_ship}. */
    String type();

    /** Definition version; a new version is a new bean with the same type and a higher number (FR-WF-08). */
    int version();

    /** Step every new instance starts in. Must be a key of {@link #steps()}. */
    String initialStep();

    /** Step name to handler. */
    Map<String, Step> steps();

    /**
     * Saga compensations by name (FR-WF-02). {@link WorkflowContext#compensation(String, Runnable)} records the
     * name durably; when a step returns {@link Next.Fail} with {@code compensate = true} the engine runs these
     * handlers in reverse registration order, each with the vars snapshot taken at registration time.
     */
    default Map<String, Step> compensations() { return Map.of(); }

    /**
     * When true, exhausting a step's retries (an unexpected exception or an activity out of attempts) triggers the
     * recorded compensations as if the step had returned {@code Next.fail(reason, true)}.
     */
    default boolean compensateOnFailure() { return false; }

    /**
     * Soft budget for a single activity body. The engine interrupts the worker thread when it is exceeded, so bodies
     * that block on interruptible I/O (JDK HttpClient, {@code Thread.sleep}) fail with a retryable timeout.
     */
    default Duration defaultActivityTimeout() { return Duration.ofSeconds(30); }
}
