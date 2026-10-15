package ai.intentoms.workflow.task;

/** Human task status constants (FR-WF-04). */
public final class TaskStatus {
    private TaskStatus() {}
    public static final String OPEN = "OPEN";
    public static final String CLAIMED = "CLAIMED";
    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELLED = "CANCELLED";
    public static final String EXPIRED = "EXPIRED";
}
