package ai.intentoms.learning.model;

import java.time.Instant;
import java.util.Map;

/**
 * A learner- or LLM-drafted changeset, not yet live (requirements doc §4/§9.1). Every state transition past DRAFT
 * is gated: EVALUATED requires a replay evaluation report, APPROVED requires a reviewer who isn't the author,
 * PROMOTED requires the experimentation tier's gate (EX-07) to have passed.
 */
public record Proposal(String proposalId, PolicyChangeset changeset, String hypothesisId, PolicyChangeset.RiskTier tier, ProposalStatus status,
                        Review criticReview, Map<String, Object> evaluationReport, String createdBy, Instant createdAt, Instant updatedAt,
                        Instant evaluatedAt, String approvedBy, Instant approvedAt, Instant promotedAt,
                        String rejectedReason, Instant rejectedAt, String rolledBackReason, Instant rolledBackAt) {

    public Proposal withStatus(ProposalStatus s, Instant now) {
        return new Proposal(proposalId, changeset, hypothesisId, tier, s, criticReview, evaluationReport, createdBy, createdAt, now,
            evaluatedAt, approvedBy, approvedAt, promotedAt, rejectedReason, rejectedAt, rolledBackReason, rolledBackAt);
    }
}
