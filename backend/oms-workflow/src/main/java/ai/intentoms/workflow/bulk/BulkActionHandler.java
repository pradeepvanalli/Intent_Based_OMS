package ai.intentoms.workflow.bulk;

import java.util.Map;
import java.util.Optional;

/**
 * SPI for a bulk action (FR-WF-07): re-source, re-promise, hold, release, cancel. Implementations are Spring beans
 * discovered by {@link BulkOperationService}; one bean per {@link #action()} name.
 */
public interface BulkActionHandler {
    /** Action name used in {@code BulkOperationService.submit}, e.g. {@code resource}, {@code hold}. */
    String action();

    /**
     * Apply the action to one subject. With {@code dryRun} the handler must not change anything and should return what
     * it would have done. The returned map is recorded per subject and later handed to {@link #rollback}.
     */
    Map<String, Object> apply(String subjectId, Map<String, Object> params, boolean dryRun);

    /** Undo a previously applied action; return a description of the undo, or empty when nothing can be undone. */
    Optional<Map<String, Object>> rollback(String subjectId, Map<String, Object> applied);
}
