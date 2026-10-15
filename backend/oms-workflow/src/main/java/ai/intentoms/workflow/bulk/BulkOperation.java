package ai.intentoms.workflow.bulk;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A submitted bulk action (FR-WF-07). Status: QUEUED, RUNNING, COMPLETED, FAILED, ROLLING_BACK, ROLLED_BACK.
 * {@code results} holds one entry per processed subject: {subjectId, status (ok|error|skipped), applied, error, rollback}.
 */
public record BulkOperation(String id, String action, Map<String, Object> params, List<String> subjectIds, boolean dryRun, int perSecond,
                            String status, int total, int done, int failed, List<Map<String, Object>> results, String error, String submittedBy,
                            Instant createdAt, Instant startedAt, Instant finishedAt, Instant rolledBackAt) {
    public boolean isFinished() { return BulkStatus.FINISHED.contains(status); }
}
