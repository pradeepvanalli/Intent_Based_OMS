package ai.intentoms.workflow.engine;

import java.time.Duration;

/**
 * Control-flow exception thrown out of {@link ai.intentoms.workflow.model.WorkflowContext#activity} when an activity
 * failed but its retry policy allows another attempt (FR-WF-02). The engine persists {@code next_wake_at = now +
 * backoff} and re-runs the same step later, replaying already-successful activities from the cache. Step code must
 * let it propagate: never catch {@code RuntimeException} around an activity call without rethrowing this.
 */
public final class RetryLater extends RuntimeException {
    private final Duration backoff;
    private final String activity;

    public RetryLater(Duration backoff, String activity, Throwable cause) {
        super("activity '" + activity + "' failed, retrying in " + backoff + ": " + (cause == null ? "" : cause.toString()), cause, false, false);
        this.backoff = backoff;
        this.activity = activity;
    }

    public Duration backoff() { return backoff; }
    public String activity() { return activity; }
}
