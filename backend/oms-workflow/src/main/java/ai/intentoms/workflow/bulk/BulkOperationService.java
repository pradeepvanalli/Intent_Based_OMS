package ai.intentoms.workflow.bulk;

import ai.intentoms.platform.jdbc.Page;

import java.util.List;
import java.util.Map;

/**
 * Bulk operations over a set of subjects with dry run, throttling and rollback (FR-WF-07). Execution is asynchronous:
 * {@link #submit} returns immediately with status QUEUED and the operation runs on a virtual thread throttled to
 * {@code perSecond} subjects per second (0 = unthrottled).
 */
public interface BulkOperationService {
    BulkOperation submit(String action, List<String> subjectIds, Map<String, Object> params, boolean dryRun, int perSecond);

    BulkOperation get(String id);

    /** Undo every successfully applied subject in reverse order via {@link BulkActionHandler#rollback}. Synchronous. */
    BulkOperation rollback(String id);

    Page<BulkOperation> list(int offset, int limit);

    /** Registered action names. */
    List<String> actions();
}
