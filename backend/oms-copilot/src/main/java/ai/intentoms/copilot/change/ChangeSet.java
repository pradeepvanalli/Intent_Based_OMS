package ai.intentoms.copilot.change;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The copilot's answer to a request (FR-CFG-01): a summary, the assumptions it made, clarifying questions when the
 * request was ambiguous, and the validated changes. Status: {@code proposed} → {@code staged} (drafts created in the
 * owning modules; FR-CFG-02) or {@code rejected}. {@code source} is {@code change} or {@code bootstrap}.
 */
public record ChangeSet(String id, ChangeRequest request, String summary, List<String> assumptions, List<String> questions, List<ProposedChange> changes,
                        String createdBy, Instant createdAt, String status, String source, Map<String, Object> meta) {
    public ChangeSet {
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        questions = questions == null ? List.of() : List.copyOf(questions);
        changes = changes == null ? List.of() : List.copyOf(changes);
        meta = meta == null ? Map.of() : Map.copyOf(meta);
    }

    public ChangeSet withChanges(List<ProposedChange> c, String newStatus) { return new ChangeSet(id, request, summary, assumptions, questions, c, createdBy, createdAt, newStatus, source, meta); }
    public ChangeSet withStatus(String newStatus, Map<String, Object> extraMeta) {
        Map<String, Object> m = new java.util.LinkedHashMap<>(meta);
        m.putAll(extraMeta);
        return new ChangeSet(id, request, summary, assumptions, questions, changes, createdBy, createdAt, newStatus, source, m);
    }
    public ProposedChange change(String changeId) {
        return changes.stream().filter(c -> c.id().equals(changeId)).findFirst().orElseThrow(() -> new ai.intentoms.platform.error.NotFoundException("Change " + changeId + " not in change set " + id));
    }
}
