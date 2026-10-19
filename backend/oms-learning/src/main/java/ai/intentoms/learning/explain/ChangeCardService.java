package ai.intentoms.learning.explain;

import ai.intentoms.learning.model.Hypothesis;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.ProposalStatus;
import ai.intentoms.learning.model.Review;
import ai.intentoms.learning.store.HypothesisRepository;
import ai.intentoms.learning.store.ProposalRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * XP-02: everything a reviewer needs on one card: the hypothesis and its evidence, the replay result with its
 * confidence interval, the critic's review, the diff, the cumulative diff against the last human-approved baseline,
 * and whether the current person may approve or reject (the author never approves their own proposal).
 */
@Service
public class ChangeCardService {
    private final ProposalRepository proposals;
    private final HypothesisRepository hypotheses;
    private final CumulativeDiffService diffs;

    private final org.springframework.beans.factory.ObjectProvider<ai.intentoms.learning.promotion.PromotionService> promotion;

    public ChangeCardService(ProposalRepository proposals, HypothesisRepository hypotheses, CumulativeDiffService diffs,
                             org.springframework.beans.factory.ObjectProvider<ai.intentoms.learning.promotion.PromotionService> promotion) {
        this.proposals = proposals;
        this.hypotheses = hypotheses;
        this.diffs = diffs;
        this.promotion = promotion;
    }

    /**
     * What the lifecycle allows next for this proposal and this person. {@code promotionConcerns} lists what promoting it
     * now would waive (a written reason is then required); {@code promotionBlocker} says why it cannot go live at all.
     */
    public record Actions(boolean canApprove, boolean canReject, String reason, boolean canEvaluate, boolean canPromote, boolean canRollback,
                          boolean canExperiment, List<String> promotionConcerns, String promotionBlocker) {
        public Actions(boolean canApprove, boolean canReject, String reason) {
            this(canApprove, canReject, reason, false, false, false, false, List.of(), null);
        }
    }

    public record ChangeCard(Proposal proposal, Hypothesis hypothesis, List<String> evidence, Map<String, Object> replay, Review criticReview,
                             List<CumulativeDiffService.KeyDiff> diff, CumulativeDiffService.CumulativeDiff cumulative, Actions actions,
                             Map<String, Object> promotionNote, String governingLearner) {}

    public ChangeCard card(String proposalId, String viewer) {
        Proposal p = proposals.get(proposalId);
        Hypothesis h = p.hypothesisId() == null ? null : hypotheses.find(p.hypothesisId()).orElse(null);
        List<String> evidence = h == null ? List.of() : h.evidence();
        Actions base = actions(p, viewer);
        List<String> concerns = List.of();
        String blocker = null;
        var promo = promotion.getIfAvailable();
        if (promo != null && p.status() == ProposalStatus.APPROVED) {
            concerns = promo.promotionConcerns(p);
            blocker = promo.promotionBlocker(p);
        }
        Actions actions = new Actions(base.canApprove(), base.canReject(), base.reason(), base.canEvaluate(), base.canPromote() && blocker == null,
            base.canRollback(), base.canExperiment(), concerns, blocker);
        return new ChangeCard(p, h, evidence, replay(p.evaluationReport()), p.criticReview(), CumulativeDiffService.diffOf(p),
            diffs.forTarget(p.changeset().target().toString(), p), actions, proposals.promotionNote(p.proposalId()).orElse(null),
            promo == null ? null : promo.governingLearner(p));
    }

    /** The replay figures of the evaluation report, or {@code {"evaluated": false}} before EX-01 has run. */
    static Map<String, Object> replay(Map<String, Object> report) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (report == null || report.isEmpty()) { m.put("evaluated", false); return m; }
        m.put("evaluated", true);
        for (String k : List.of("method", "liftEstimate", "confidenceInterval", "sampleSize", "episodes", "guardrails", "shadow", "canary", "estimator", "clipping",
                "ips", "rewards", "reason", "next", "caveat", "baselineReward", "proposedRewardEstimate", "fraudLabels"))
            if (report.containsKey(k)) m.put(k, report.get(k));
        // E-2: the lift is a difference in mean reward (the objective's weighted sum, not a 0-1 scale, E3-N3), not a percentage; say so on the card.
        if (report.get("liftEstimate") instanceof Number) m.put("liftUnit", "reward");
        List<String> concerns = new java.util.ArrayList<>();
        String ips = ai.intentoms.learning.promotion.PromotionGuard.ipsWorse(report);
        if (ips != null) concerns.add(ips);
        if (report.get("fraudLabels") instanceof Map<?, ?> fl && "higher_cost".equals(fl.get("verdict"))) {
            concerns.add("on labelled fraud history it raises the expected cost by " + fl.get("expectedCostChange"));
        }
        m.put("concerns", concerns);
        return m;
    }

    /** Defect 2: exactly the moves {@link ai.intentoms.learning.model.ProposalLifecycle} allows from the current status. */
    static Actions actions(Proposal p, String viewer) {
        ProposalStatus s = p.status();
        boolean canApproveStatus = ai.intentoms.learning.model.ProposalLifecycle.allowed(s, ProposalStatus.APPROVED);
        boolean canReject = ai.intentoms.learning.model.ProposalLifecycle.allowed(s, ProposalStatus.REJECTED);
        boolean canEvaluate = ai.intentoms.learning.model.ProposalLifecycle.allowed(s, ProposalStatus.EVALUATED);
        boolean canPromote = s == ProposalStatus.APPROVED;
        boolean canRollback = s == ProposalStatus.PROMOTED || s == ProposalStatus.CANARY;
        boolean canExperiment = s == ProposalStatus.DRAFT || s == ProposalStatus.EVALUATED || s == ProposalStatus.APPROVED;
        boolean author = viewer != null && viewer.equals(p.createdBy());
        String reason = !canApproveStatus ? (s == ProposalStatus.DRAFT ? "test it on past decisions before approving" : "proposal is " + s)
            : author ? "the author of a proposal cannot approve it" : null;
        return new Actions(canApproveStatus && !author, canReject, reason, canEvaluate, canPromote, canRollback, canExperiment, List.of(), null);
    }
}
