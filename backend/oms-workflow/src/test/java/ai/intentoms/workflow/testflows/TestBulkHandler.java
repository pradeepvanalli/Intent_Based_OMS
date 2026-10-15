package ai.intentoms.workflow.testflows;

import ai.intentoms.workflow.bulk.BulkActionHandler;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Records applied subjects in memory; subject "bad" always fails (FR-WF-07). */
@Component
public class TestBulkHandler implements BulkActionHandler {
    public static final String ACTION = "test.touch";
    public static final Map<String, Map<String, Object>> APPLIED = new ConcurrentHashMap<>();

    @Override public String action() { return ACTION; }

    @Override
    public Map<String, Object> apply(String subjectId, Map<String, Object> params, boolean dryRun) {
        if (subjectId.startsWith("bad")) throw new IllegalStateException("subject " + subjectId + " cannot be touched");
        Map<String, Object> applied = Map.of("subjectId", subjectId, "tag", String.valueOf(params.get("tag")), "dryRun", dryRun);
        if (!dryRun) APPLIED.put(subjectId, applied);
        return applied;
    }

    @Override
    public Optional<Map<String, Object>> rollback(String subjectId, Map<String, Object> applied) {
        return APPLIED.remove(subjectId) == null ? Optional.empty() : Optional.of(Map.of("untagged", applied.get("tag")));
    }
}
