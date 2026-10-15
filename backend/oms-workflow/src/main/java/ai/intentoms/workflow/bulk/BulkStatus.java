package ai.intentoms.workflow.bulk;

import java.util.Set;

/** Bulk operation status constants (FR-WF-07). */
public final class BulkStatus {
    private BulkStatus() {}
    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String ROLLING_BACK = "ROLLING_BACK";
    public static final String ROLLED_BACK = "ROLLED_BACK";
    public static final Set<String> FINISHED = Set.of(COMPLETED, FAILED, ROLLED_BACK);
}
