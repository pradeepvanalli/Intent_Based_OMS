package ai.intentoms.learning;

import ai.intentoms.decision.model.Band;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.Layer;
import ai.intentoms.decision.model.Mode;
import ai.intentoms.learning.model.ArtifactRef;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.PolicyChangeset;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.ProposalStatus;
import ai.intentoms.platform.ids.Ids;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Builders shared by the learning integration tests. */
public final class LearningFixtures {
    private LearningFixtures() {}

    public static Proposal threshold(String decision, double from, double to, String createdBy, String hypothesisId) {
        PolicyChangeset cs = new PolicyChangeset(new ArtifactRef("decision", decision), 1, List.of(new PolicyChange.ThresholdChange(decision, "act", from, to)),
            hypothesisId, PolicyChangeset.RiskTier.T1);
        Instant now = Instant.now();
        return new Proposal(Ids.prefixed("prop"), cs, hypothesisId, PolicyChangeset.RiskTier.T1, ProposalStatus.DRAFT, null, null, createdBy, now, now,
            null, null, null, null, null, null, null, null);
    }

    public static DecisionRecord record(String decision, String subjectType, String subjectId, Band band, Map<String, Object> details, Instant at) {
        return new DecisionRecord(Ids.prefixed("dr"), decision, 1, Mode.LIVE, subjectType, subjectId, subjectId, Layer.L1_SYSTEM_ONE, band, "act", "x", Map.of(), 0.9,
            "heuristic", "test", "hash", Map.of(), Map.of(), List.of(), List.of(), 1, 0, 0, false, null, "system:test", details, null, null, null, null, Map.of(), at,
            Map.of(), List.of("x", "y"), "x", 1.0, 1);
    }
}
