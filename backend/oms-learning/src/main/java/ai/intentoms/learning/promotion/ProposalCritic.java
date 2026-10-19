package ai.intentoms.learning.promotion;

import ai.intentoms.learning.model.Review;

import java.util.Optional;

/**
 * XP-02 SPI: reviews an evaluated proposal's changeset against its evaluation report and stores the review on the
 * proposal. Implemented in oms-copilot (the critic model when a key and budget allow, else the rule-based critic).
 * The review is evidence for the person who approves; it never approves, rejects or blocks on its own.
 */
public interface ProposalCritic {
    /** Reviews and stores; empty when the proposal has no evaluation report yet or the review could not be made. */
    Optional<Review> review(String proposalId);
}
