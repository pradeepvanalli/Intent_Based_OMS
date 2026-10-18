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

    public ChangeCardService(ProposalRepository proposals, HypothesisRepository hypotheses, CumulativeDiffService diffs) {
        this.proposals = proposals;
        this.hypotheses = hypotheses;
        this.diffs = diffs;
    }

    public record Actions(boolean canApprove, boolean canReject, String reason) {}

    public record ChangeCard(Proposal proposal, Hypothesis hypothesis, List<String> evidence, Map<String, Object> replay, Review criticReview,
                             List<CumulativeDiffService.KeyDiff> diff, CumulativeDiffService.CumulativeDiff cumulative, Actions actions) {}

    public ChangeCard card(String proposalId, String viewer) {
        Proposal p = proposals.get(proposalId);
        Hypothesis h = p.hypothesisId() == null ? null : hypotheses.find(p.hypothesisId()).orElse(null);
        List<String> evidence = h == null ? List.of() : h.evidence();
        return new ChangeCard(p, h, evidence, replay(p.evaluationReport()), p.criticReview(), CumulativeDiffService.diffOf(p),
            diffs.forTarget(p.changeset().target().toString(), p), actions(p, viewer));
    }

    /** The replay figures of the evaluation report, or {@code {"evaluated": false}} before EX-01 has run. */
    static Map<String, Object> replay(Map<String, Object> report) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (report == null || report.isEmpty()) { m.put("evaluated", false); return m; }
        m.put("evaluated", true);
        for (String k : List.of("method", "liftEstimate", "confidenceInterval", "sampleSize", "episodes", "guardrails", "shadow", "estimator", "clipping"))
            if (report.containsKey(k)) m.put(k, report.get(k));
        return m;
    }

    static Actions actions(Proposal p, String viewer) {
        boolean open = p.status() == ProposalStatus.DRAFT || p.status() == ProposalStatus.EVALUATED;
        if (!open) return new Actions(false, false, "proposal is " + p.status());
        if (viewer != null && viewer.equals(p.createdBy())) return new Actions(false, true, "the author of a proposal cannot approve it");
        return new Actions(true, true, null);
    }
}
