package ai.intentoms.learning.model;

import java.util.List;

/** The author LLM's only output: a diff against a versioned artifact (requirements doc §6.2). */
public record PolicyChangeset(ArtifactRef target, int baseVersion, List<PolicyChange> changes, String motivatedBy, RiskTier tier) {
    public enum RiskTier { T0, T1, T2, T3 }
}
