package ai.intentoms.workflow.engine;

/** An activity exhausted its retry policy; the step fails and the instance is FAILED (or compensated) (FR-WF-02). */
public final class ActivityFailedException extends RuntimeException {
    private final String activity;
    private final int attempts;

    public ActivityFailedException(String activity, int attempts, Throwable cause) {
        super("activity '" + activity + "' failed after " + attempts + " attempt(s): " + (cause == null ? "" : cause.toString()), cause);
        this.activity = activity;
        this.attempts = attempts;
    }

    public String activity() { return activity; }
    public int attempts() { return attempts; }
}
