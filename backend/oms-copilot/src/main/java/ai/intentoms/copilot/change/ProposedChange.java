package ai.intentoms.copilot.change;

import java.util.List;
import java.util.Map;

/**
 * One artefact change inside a {@link ChangeSet}. {@code before} is the current artefact text (null when new),
 * {@code after} the proposed text, {@code diff} a unified diff computed by the service, {@code validation} the
 * deterministic validator result, {@code tests} generated golden cases (decision kinds), {@code requiresApprovals} how
 * many human approvals the owning pipeline will demand, {@code staged} where the draft went after staging
 * ({@code {type: decision_draft, name, version}} or {@code {type: draft_artifact, artifactId}}).
 */
public record ProposedChange(String id, ChangeKind kind, String target, String rationale, String before, String after, String diff,
                             ValidationResult validation, List<GeneratedTest> tests, String riskTier, int requiresApprovals, boolean repaired,
                             Map<String, Object> staged, String stageError) {
    public ProposedChange {
        tests = tests == null ? List.of() : List.copyOf(tests);
        riskTier = riskTier == null ? "T1" : riskTier;
    }

    public ProposedChange withAfter(String newAfter, boolean wasRepaired) {
        return new ProposedChange(id, kind, target, rationale, before, newAfter, diff, validation, tests, riskTier, requiresApprovals, wasRepaired, staged, stageError);
    }
    public ProposedChange withValidation(String newBefore, ValidationResult v, String newDiff, int approvals) {
        return new ProposedChange(id, kind, target, rationale, newBefore, after, newDiff, v, tests, riskTier, approvals, repaired, staged, stageError);
    }
    public ProposedChange withStaged(Map<String, Object> s, String error) {
        return new ProposedChange(id, kind, target, rationale, before, after, diff, validation, tests, riskTier, requiresApprovals, repaired, s, error);
    }
}
