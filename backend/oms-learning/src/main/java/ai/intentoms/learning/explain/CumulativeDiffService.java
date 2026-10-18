package ai.intentoms.learning.explain;

import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.store.ProposalRepository;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * XP-02 / §11 "runaway changes": many small automatic promotions can add up to a large drift, so every change card
 * shows the cumulative diff against the last baseline a person approved. For a target ({@code decision:<name>} or
 * {@code parameter:<name>}): the baseline is the most recent promoted proposal a human approved (none means the
 * original configuration); every later promotion is folded per changed key into first {@code from} to last
 * {@code to}, and the pending change can be added on top to show where approving it would leave the target.
 */
@Service
public class CumulativeDiffService {
    private final JdbcClient jdbc;
    private final ProposalRepository proposals;

    public CumulativeDiffService(JdbcClient jdbc, ProposalRepository proposals) {
        this.jdbc = jdbc;
        this.proposals = proposals;
    }

    /** One changed key: where it stood at the baseline, where it is now, and over how many promotions. */
    public record KeyDiff(String key, String type, Object from, Object to, int steps) {}

    public record Baseline(String proposalId, String approvedBy, Instant approvedAt, Instant promotedAt) {
        static final Baseline ORIGINAL = new Baseline(null, null, null, null);
    }

    public record CumulativeDiff(String target, Baseline baseline, int automaticPromotionsSinceBaseline, List<KeyDiff> sinceBaseline,
                                 List<KeyDiff> withPending, List<String> promotedProposalIds) {}

    public CumulativeDiff forTarget(String target) { return forTarget(target, null); }

    /** @param pending a not-yet-promoted proposal to add on top, or null */
    public CumulativeDiff forTarget(String target, Proposal pending) {
        List<Proposal> promoted = promotedFor(target);
        int baselineIdx = -1;
        for (int i = promoted.size() - 1; i >= 0; i--) if (isHuman(promoted.get(i).approvedBy())) { baselineIdx = i; break; }
        Baseline baseline = baselineIdx < 0 ? Baseline.ORIGINAL
            : new Baseline(promoted.get(baselineIdx).proposalId(), promoted.get(baselineIdx).approvedBy(), promoted.get(baselineIdx).approvedAt(), promoted.get(baselineIdx).promotedAt());
        List<Proposal> since = promoted.subList(baselineIdx + 1, promoted.size());
        Map<String, KeyDiff> folded = new LinkedHashMap<>();
        for (Proposal p : since) fold(folded, p);
        List<KeyDiff> sinceBaseline = List.copyOf(folded.values());
        List<KeyDiff> withPending = sinceBaseline;
        if (pending != null && since.stream().noneMatch(p -> p.proposalId().equals(pending.proposalId()))) {
            Map<String, KeyDiff> projected = new LinkedHashMap<>(folded);
            fold(projected, pending);
            withPending = List.copyOf(projected.values());
        }
        return new CumulativeDiff(target, baseline, since.size(), sinceBaseline, withPending, promoted.stream().map(Proposal::proposalId).toList());
    }

    /** The individual changes of one proposal as key diffs (the card's own diff). */
    public static List<KeyDiff> diffOf(Proposal p) {
        Map<String, KeyDiff> m = new LinkedHashMap<>();
        fold(m, p);
        return List.copyOf(m.values());
    }

    private List<Proposal> promotedFor(String target) {
        List<String> ids = jdbc.sql("select proposal_id from proposal where tenant_id = :t and target_ref = :r and status = 'PROMOTED' and promoted_at is not null order by promoted_at")
            .param("t", TenantContext.require()).param("r", target).query(String.class).list();
        List<Proposal> out = new ArrayList<>();
        for (String id : ids) proposals.find(id).ifPresent(out::add);
        return out;
    }

    static boolean isHuman(String approvedBy) { return approvedBy != null && !approvedBy.isBlank() && !approvedBy.startsWith("system:"); }

    static void fold(Map<String, KeyDiff> acc, Proposal p) {
        for (PolicyChange c : p.changeset().changes()) {
            KeyDiff d = switch (c) {
                case PolicyChange.ThresholdChange tc -> new KeyDiff("threshold:" + tc.decision() + ":" + tc.band(), "ThresholdChange", tc.from(), tc.to(), 1);
                case PolicyChange.WeightChange wc -> new KeyDiff("weight:" + wc.target() + ":" + wc.weightKey(), "WeightChange", wc.from(), wc.to(), 1);
                case PolicyChange.ParameterChange pc -> new KeyDiff("parameter:" + pc.parameter(), "ParameterChange", pc.from(), pc.to(), 1);
                case PolicyChange.RuleAdd ra -> new KeyDiff("rule:" + ra.decision() + ":" + ra.expression(), "RuleAdd", null, ra.thenValue(), 1);
                case PolicyChange.RuleRetire rr -> new KeyDiff("rule:" + rr.decision() + ":" + rr.ruleId(), "RuleRetire", rr.ruleId(), null, 1);
                case PolicyChange.AllowListChange al -> new KeyDiff("allow:" + al.listName() + ":" + al.entry(), "AllowListChange", !al.add(), al.add(), 1);
            };
            KeyDiff prev = acc.get(d.key());
            acc.put(d.key(), prev == null ? d : new KeyDiff(d.key(), d.type(), prev.from(), d.to(), prev.steps() + 1));
        }
    }
}
