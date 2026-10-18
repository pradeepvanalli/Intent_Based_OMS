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
 * proposal, may it promote automatically right now, and if not, why. Pure and unit-tested; the side effects live in
 * {@link PromotionService}.
 *
 * <p>Autonomy x tier (GV-01, EX-07): L0 never promotes. L1 auto-promotes a T0 change only once both replay and a
 * shadow run passed; anything above T0 needs approval. L2 auto-promotes T0; T1 needs approval. L3 auto-promotes T0
 * and T1 within the change budget. T2 always needs approval; T3 is never autonomous, not even by manual promotion of
 * an auto-drafted change. Some changes are never autonomous whatever the tier or level: anything touching refunds,
 * cancellations, pricing, the objective profile or autonomy levels needs a human, and adding refund or cancel to an
 * autonomous-action allow-list is refused outright.
 *
 * <p>Also: GV-03 a kill-switched or paused learner never promotes. GV-02 a step above the learner's {@code maxStep}, or
 * past {@code maxPromotionsPerWeek}, is held for approval rather than clipped. GV-05 inside a freeze window nothing
 * promotes, automatically or by approval. GV-07 a postal-code-scoped change always needs approval.
 */
public final class PromotionGuard {
    private PromotionGuard() {}

    public enum Verdict { AUTO_PROMOTE, NEEDS_APPROVAL, BLOCKED }

    public record Result(Verdict verdict, String reason) {
        static Result auto(String reason) { return new Result(Verdict.AUTO_PROMOTE, reason); }
        static Result needsApproval(String reason) { return new Result(Verdict.NEEDS_APPROVAL, reason); }
        static Result blocked(String reason) { return new Result(Verdict.BLOCKED, reason); }
    }

    private static final List<String> NEVER_AUTONOMOUS = List.of("refund", "cancel", "pric", "objective", "autonomy");

    public static Result evaluate(Proposal p, LearnerState learner, List<FreezeWindow> freezeWindows, Instant now, int promotionsInTrailingWeek) {
        if (isInsideAnyWindow(freezeWindows, now)) return Result.blocked("inside a tenant freeze window");
        if (!learner.enabled()) return Result.blocked("learner '" + learner.learnerKey() + "' is kill-switched off");
        if (learner.paused()) return Result.blocked("learner '" + learner.learnerKey() + "' is paused: " + learner.pausedReason());
        String refused = refusedOutright(p.changeset());
        if (refused != null) return Result.blocked(refused);
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
        if (tier == PolicyChangeset.RiskTier.T3) return Result.blocked("T3 decisions are never autonomous (requirements doc EX-07)");
        if (tier == PolicyChangeset.RiskTier.T2) return Result.needsApproval("T2 always requires approval (requirements doc EX-07)");
        String sensitive = neverAutonomous(p.changeset());
        if (sensitive != null) return Result.needsApproval(sensitive);
        AutonomyLevel level = learner.autonomyLevel();
        if (level == AutonomyLevel.L0_OBSERVE) return Result.blocked("learner is observe-only (L0) and never promotes");
        if (tier == PolicyChangeset.RiskTier.T0) {
            if (level != AutonomyLevel.L1_RECOMMEND) return Result.auto("T0 at " + level + ": replay passed, auto-promotes");
            Boolean shadowOk = shadowPassed(p.evaluationReport());
            if (Boolean.TRUE.equals(shadowOk)) return Result.auto("T0 at L1: replay and shadow run both passed, auto-promotes");
            return Result.needsApproval(shadowOk == null ? "T0 at L1 auto-promotes only after a shadow run; none has completed yet"
                : "T0 at L1: the shadow run did not pass");
        }
        return level == AutonomyLevel.L3_AUTONOMOUS
            ? Result.auto("T1 at learner autonomy L3: auto-promotes within the change budget")
            : Result.needsApproval("T1 auto-promotes only at learner autonomy L3 (currently " + level + ")");
    }

    /** A human clicking "promote" on an already-APPROVED proposal still goes through every check except the
     * tier/autonomy auto-promote gate itself — approval substitutes for that one check, not for freeze windows,
     * guardrails, lift, or the postal-code review rule. */
    public static Result evaluateManualPromote(Proposal p, LearnerState learner, List<FreezeWindow> freezeWindows, Instant now) {
        if (isInsideAnyWindow(freezeWindows, now)) return Result.blocked("inside a tenant freeze window");
        if (!learner.enabled()) return Result.blocked("learner '" + learner.learnerKey() + "' is kill-switched off");
        if (learner.paused()) return Result.blocked("learner '" + learner.learnerKey() + "' is paused: " + learner.pausedReason());
        String refused = refusedOutright(p.changeset());
        if (refused != null) return Result.blocked(refused);
        if (p.status() != ai.intentoms.learning.model.ProposalStatus.APPROVED) return Result.blocked("proposal must be APPROVED before a human can promote it");
        Boolean liftOk = positiveLift(p.evaluationReport());
        if (liftOk != null && !liftOk) return Result.blocked("evaluation shows no positive lift; re-evaluate or reject instead of promoting");
        if (p.tier() == PolicyChangeset.RiskTier.T3) return Result.blocked("T3 decisions are never autonomous, including by manual promotion of an auto-drafted change");
        return Result.auto("approved by a human, promoting");
    }

    /** Changes no approval can make: putting refunds or cancellations on an autonomous-action allow-list. */
    static String refusedOutright(PolicyChangeset cs) {
        for (PolicyChange c : cs.changes()) {
            if (c instanceof PolicyChange.AllowListChange a && a.add()) {
                String e = a.entry().toLowerCase(java.util.Locale.ROOT);
                if (e.contains("refund") || e.contains("cancel")) return "refunds and cancellations are never autonomous; '" + a.entry() + "' cannot be added to " + a.listName();
            }
        }
        return null;
    }

    /** Changes that may be promoted, but only by a human: refunds, cancellations, pricing, objective, autonomy. */
    static String neverAutonomous(PolicyChangeset cs) {
        List<String> touched = new java.util.ArrayList<>();
        touched.add(cs.target() == null ? "" : cs.target().toString());
        for (PolicyChange c : cs.changes()) {
            switch (c) {
                case PolicyChange.ParameterChange pc -> touched.add(pc.parameter());
                case PolicyChange.WeightChange wc -> touched.add(wc.target() + " " + wc.weightKey());
                case PolicyChange.AllowListChange a -> touched.add(a.listName() + " " + a.entry());
                case PolicyChange.RuleAdd ra -> touched.add(ra.decision() + " " + ra.thenValue());
                case PolicyChange.RuleRetire rr -> touched.add(rr.decision());
                case PolicyChange.ThresholdChange tc -> touched.add(tc.decision());
            }
        }
        for (String t : touched) {
            String s = t == null ? "" : t.toLowerCase(java.util.Locale.ROOT);
            for (String w : NEVER_AUTONOMOUS) if (s.contains(w)) return "changes touching '" + t + "' are never autonomous and need a human approver";
        }
        return null;
    }

    /** {@code evaluationReport.shadow.passed}, written when a shadow experiment for the proposal completes. */
    static Boolean shadowPassed(Map<String, Object> report) {
        if (report == null || !(report.get("shadow") instanceof Map<?, ?> m)) return null;
        return m.get("passed") instanceof Boolean b ? b : null;
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
