package ai.intentoms.workflow.engine;

import java.time.Duration;

/** An activity body exceeded {@code WorkflowDefinition.defaultActivityTimeout()} and was interrupted (FR-WF-02). */
public final class ActivityTimeoutException extends RuntimeException {
    public ActivityTimeoutException(String activity, Duration timeout, Throwable cause) {
        super("activity '" + activity + "' exceeded its timeout of " + timeout, cause);
    }
}
