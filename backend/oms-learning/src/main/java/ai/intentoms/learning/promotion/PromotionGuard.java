package ai.intentoms.learning.promotion;

import ai.intentoms.learning.model.AutonomyLevel;
import ai.intentoms.learning.model.LearnerState;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.PolicyChangeset;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.Review;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * EX-07's promotion gate by tier, plus GV-01/02/03/05/07, as one pure decision: given everything known about a
 * proposal, may it promote automatically right now, and if not, why. Pure/unit-tested on purpose — the actual
 * side effects (publishing, writing facts, appending ledger events) live in {@link PromotionService}.
 *
 * <p>Tier rule (requirements doc EX-07): T0 promotes automatically once replay (positive lift, CI excludes zero)
 * and the critic review both pass. T1 promotes automatically only when its learner's autonomy is L3; otherwise it
 * needs approval. T2 always needs approval regardless of autonomy. T3 is never autonomous. GV-01/03: a learner at
 * L0/L1, or disabled (kill switch), never auto-promotes regardless of tier. GV-02: a promotion whose numeric step
 * exceeds the learner's {@code maxStep}, or that would exceed {@code maxPromotionsPerWeek}, is held for approval
 * rather than silently clipped. GV-05: inside a freeze window, nothing promotes, automatically or by approval
 * action — the window blocks the gate outright. GV-07: a postal-code-scoped change always needs approval,
 * regardless of tier or autonomy.
 */
public final class PromotionGuard {
    private PromotionGuard() {}

    public enum Verdict { AUTO_PROMOTE, NEEDS_APPROVAL, BLOCKED }

    public record Result(Verdict verdict, String reason) {
        static Result auto(String reason) { return new Result(Verdict.AUTO_PROMOTE, reason); }
        static Result needsApproval(String reason) { return new Result(Verdict.NEEDS_APPROVAL, reason); }
        static Result blocked(String reason) { return new Result(Verdict.BLOCKED, reason); }
    }

    public static Result evaluate(Proposal p, LearnerState learner, List<FreezeWindow> freezeWindows, Instant now, int promotionsInTrailingWeek) {
        if (isInsideAnyWindow(freezeWindows, now)) return Result.blocked("inside a tenant freeze window");
        if (!learner.enabled()) return Result.blocked("learner '" + learner.learnerKey() + "' is kill-switched off");
        if (isPostalCodeScoped(p.changeset())) return Result.needsApproval("postal-code-scoped restriction always needs a reason code and human review (GV-07)");
        if (p.status() != ai.intentoms.learning.model.ProposalStatus.EVALUATED && p.status() != ai.intentoms.learning.model.ProposalStatus.APPROVED) {
            return Result.blocked("proposal is " + p.status() + ", not evaluated or approved");
        }
        Boolean liftOk = positiveLift(p.evaluationReport());
        if (liftOk == null) return Result.needsApproval("no evaluation report yet (EX-01 must run before promotion)");
        if (!liftOk) return Result.blocked("evaluation shows no positive lift (confidence interval includes or is below zero)");
        if (p.criticReview() != null && p.criticReview().verdict() == Review.Verdict.RISKY) return Result.needsApproval("critic review verdict is RISKY");

        Double step = numericStep(p.changeset());
        if (step != null && learner.maxStep() != null && step > learner.maxStep()) {
            return Result.needsApproval(String.format(java.util.Locale.ROOT, "change of %.4f exceeds the learner's max step %.4f (GV-02)", step, learner.maxStep()));
        }
        if (promotionsInTrailingWeek >= learner.maxPromotionsPerWeek()) {
            return Result.needsApproval("learner has already promoted " + promotionsInTrailingWeek + "/" + learner.maxPromotionsPerWeek() + " changes this week (GV-02)");
        }

        PolicyChangeset.RiskTier tier = p.tier();
        return switch (tier) {
            case T0 -> Result.auto("T0: replay and critic review passed, auto-promotes without approval");
            case T1 -> learner.autonomyLevel() == AutonomyLevel.L3_AUTONOMOUS
                ? Result.auto("T1 at learner autonomy L3: auto-promotes")
                : Result.needsApproval("T1 requires learner autonomy L3 to auto-promote (currently " + learner.autonomyLevel() + ")");
            case T2 -> Result.needsApproval("T2 always requires approval (requirements doc EX-07)");
            case T3 -> Result.blocked("T3 decisions are never autonomous (requirements doc EX-07)");
        };
    }

    /** A human clicking "promote" on an already-APPROVED proposal still goes through every check except the
     * tier/autonomy auto-promote gate itself — approval substitutes for that one check, not for freeze windows,
     * guardrails, lift, or the postal-code review rule. */
    public static Result evaluateManualPromote(Proposal p, LearnerState learner, List<FreezeWindow> freezeWindows, Instant now) {
        if (isInsideAnyWindow(freezeWindows, now)) return Result.blocked("inside a tenant freeze window");
        if (!learner.enabled()) return Result.blocked("learner '" + learner.learnerKey() + "' is kill-switched off");
        if (p.status() != ai.intentoms.learning.model.ProposalStatus.APPROVED) return Result.blocked("proposal must be APPROVED before a human can promote it");
        Boolean liftOk = positiveLift(p.evaluationReport());
        if (liftOk != null && !liftOk) return Result.blocked("evaluation shows no positive lift; re-evaluate or reject instead of promoting");
        if (p.tier() == PolicyChangeset.RiskTier.T3) return Result.blocked("T3 decisions are never autonomous, including by manual promotion of an auto-drafted change");
        return Result.auto("approved by a human, promoting");
    }

    static boolean isPostalCodeScoped(PolicyChangeset cs) {
        for (PolicyChange c : cs.changes()) {
            String s = switch (c) {
                case PolicyChange.ParameterChange pc -> pc.parameter();
                case PolicyChange.RuleAdd ra -> ra.expression();
                default -> "";
            };
            if (s != null && (s.toLowerCase(java.util.Locale.ROOT).contains("zip") || s.toLowerCase(java.util.Locale.ROOT).contains("postal"))) return true;
        }
        return false;
    }

    static Double numericStep(PolicyChangeset cs) {
        for (PolicyChange c : cs.changes()) {
            if (c instanceof PolicyChange.ThresholdChange tc) return Math.abs(tc.to() - tc.from());
            if (c instanceof PolicyChange.WeightChange wc) return Math.abs(wc.to() - wc.from());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static Boolean positiveLift(Map<String, Object> report) {
        if (report == null) return null;
        Object ci = report.get("confidenceInterval");
        if (ci instanceof Map<?, ?> m) {
            Object lower = m.get("lower");
            if (lower instanceof Number n) return n.doubleValue() > 0;
        }
        Object lift = report.get("liftEstimate");
        return lift instanceof Number n && n.doubleValue() > 0;
    }

    static boolean isInsideAnyWindow(List<FreezeWindow> windows, Instant now) {
        if (windows == null || windows.isEmpty()) return false;
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        for (FreezeWindow w : windows) if (!today.isBefore(w.from()) && !today.isAfter(w.to())) return true;
        return false;
    }

    public record FreezeWindow(LocalDate from, LocalDate to) {}
}
