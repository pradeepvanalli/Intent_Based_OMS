package ai.intentoms.workflow.model;

import java.util.Set;

/** Instance status constants and groupings used by the engine, the API and other modules. */
public final class WorkflowStatus {
    private WorkflowStatus() {}
    public static final String RUNNING = "RUNNING";
    public static final String WAITING_SIGNAL = "WAITING_SIGNAL";
    public static final String WAITING_TIMER = "WAITING_TIMER";
    public static final String WAITING_TASK = "WAITING_TASK";
    public static final String COMPENSATING = "COMPENSATING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String COMPENSATED = "COMPENSATED";
    public static final String CANCELLED = "CANCELLED";

    /** Statuses a worker may pick up. */
    public static final Set<String> ACTIVE = Set.of(RUNNING, WAITING_SIGNAL, WAITING_TIMER, WAITING_TASK, COMPENSATING);
    public static final Set<String> TERMINAL = Set.of(COMPLETED, FAILED, COMPENSATED, CANCELLED);

    public static boolean isTerminal(String s) { return TERMINAL.contains(s); }
}
