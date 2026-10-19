package ai.intentoms.learning.promotion;

import ai.intentoms.learning.model.AutonomyLevel;
import ai.intentoms.learning.model.LearnerState;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.PolicyChangeset;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.ProposalStatus;
import ai.intentoms.learning.model.Review;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * EX-07's promotion gate by tier, plus GV-01/02/03/05/07, as one pure decision. Side effects live in
 * {@link PromotionService}.
 *
 * <p><b>Automatic promotion</b> follows the requirements doc's EX-07 wording: "T0 promotes automatically after replay
 * and shadow; T1 automatically at autonomy L3, else with approval; T2 always with approval; T3 never autonomous." So
 * every automatic promotion needs a replay whose confidence interval is above zero <em>and</em> a passed shadow run
 * (§5: "every one of its changes passes replay, shadow and canary first"; the canary stays optional, the shadow does
 * not). This replaces an earlier reading that let T0 at L2/L3 promote on replay alone. Autonomy (§4) then decides how
 * far a learner may go: L0 observes and never promotes; at L1 and L2 only T0 is automatic; at L3 T0 and T1 are.
 *
 * <p><b>Manual promotion</b> of an APPROVED proposal: approval replaces the tier/autonomy check, nothing else. The change
 * needs evidence — a replay for a decision change, a passed shadow run (or an observational comparison) for a config or
 * learned-value change — and the evidence must not cross zero or point the wrong way; the change must fit the target's
 * weekly budget and the learner's max step (GV-02); the shadow run, if any, must have passed; the critic must not have
 * called it risky. A person can still promote despite any of those by giving a written reason, which is recorded on the
 * ledger and on the proposal ({@link Verdict#NEEDS_OVERRIDE} names what they would be waiving). Nothing can override a
 * freeze window, a kill switch, a switched-off or paused learner, a T3 change, or refunds and cancellations on an
 * autonomous allow-list.
 *
 * <p>Always: changes touching refunds, cancellations, pricing, the objective or autonomy are never automatic; a change
 * scoped to postal codes needs a person and a reason code (GV-07).
 */
public final class PromotionGuard {
    private PromotionGuard() {}

    public enum Verdict { AUTO_PROMOTE, NEEDS_APPROVAL, NEEDS_OVERRIDE, BLOCKED }

    /** {@code waivable}: the checks a person would be overriding (manual promotion only). */
    public record Result(Verdict verdict, String reason, List<String> waivable) {
        static Result auto(String reason) { return new Result(Verdict.AUTO_PROMOTE, reason, List.of()); }
        static Result needsApproval(String reason) { return new Result(Verdict.NEEDS_APPROVAL, reason, List.of()); }
        static Result blocked(String reason) { return new Result(Verdict.BLOCKED, reason, List.of()); }
    }

    /**
     * What the gate knows besides the proposal. {@code learningOffReason} is non-null while the global or tenant kill
     * switch is on. {@code targetPromotionsThisWeek} counts every promotion of the same target in the last 7 days;
     * {@code targetBudgetPerWeek} is the governing learner's {@code maxPromotionsPerWeek} (default 1).
     */
    public record Context(LearnerState learner, List<FreezeWindow> freezeWindows, Instant now, String learningOffReason,
                          int targetPromotionsThisWeek, int targetBudgetPerWeek) {
        public Context {
            freezeWindows = freezeWindows == null ? List.of() : freezeWindows;
            targetBudgetPerWeek = Math.max(0, targetBudgetPerWeek);
        }
    }

    /** GV-02's example: a threshold (or any other 0..1 value) moves at most 0.05 per promotion unless the learner's own max step says otherwise. */
    public static final double DEFAULT_MAX_STEP = 0.05;
    private static final List<String> NEVER_AUTONOMOUS = List.of("refund", "cancel", "pric", "objective", "autonomy");
    private static final List<String> POSTAL_WORDS = List.of("zip", "postal", "postcode", "post_code", "pincode", "pin_code");
    static final int MIN_OVERRIDE_REASON = 10;

    // ------------------------------------------------------------------ automatic

    public static Result evaluate(Proposal p, Context ctx) {
        Result hard = hardStops(p, ctx);
        if (hard != null) return hard;
        if (isPostalCodeScoped(p.changeset())) return Result.needsApproval("postal-code-scoped restriction always needs a reason code and human review (GV-07)");
        if (p.status() != ProposalStatus.EVALUATED && p.status() != ProposalStatus.APPROVED) return Result.blocked("proposal is " + p.status() + ", not evaluated or approved");

        PolicyChangeset.RiskTier tier = p.tier();
        if (tier == PolicyChangeset.RiskTier.T3) return Result.blocked("T3 decisions are never autonomous (requirements doc EX-07)");
        if (tier == PolicyChangeset.RiskTier.T2) return Result.needsApproval("T2 always requires approval (requirements doc EX-07)");
        String sensitive = neverAutonomous(p.changeset());
        if (sensitive != null) return Result.needsApproval(sensitive);
        AutonomyLevel level = ctx.learner().autonomyLevel();
        if (level == AutonomyLevel.L0_OBSERVE) return Result.blocked("learner is observe-only (L0) and never promotes");

        Boolean liftOk = positiveLift(p.evaluationReport());
        if (liftOk == null) return Result.needsApproval("no replay evaluation with a confidence interval yet (EX-01 runs before promotion)");
        if (Boolean.TRUE.equals(significantlyWorse(p.evaluationReport()))) return Result.blocked("replay shows the change makes outcomes worse");
        if (!liftOk) return Result.needsApproval("replay shows no clear improvement (the confidence interval includes zero)");
        Boolean shadowOk = shadowPassed(p.evaluationReport());
        if (shadowOk == null) return Result.needsApproval(tier + " promotes automatically only after replay and a shadow run (EX-07); no shadow run has completed yet");
        if (!shadowOk) return Result.needsApproval("the shadow run did not pass");
        if (p.criticReview() != null && p.criticReview().verdict() == Review.Verdict.RISKY) return Result.needsApproval("critic review verdict is RISKY");
        String step = stepViolation(p.changeset(), ctx.learner());
        if (step != null) return Result.needsApproval(step);
        String budget = budgetViolation(p, ctx);
        if (budget != null) return Result.needsApproval(budget);

        if (tier == PolicyChangeset.RiskTier.T0) return Result.auto("T0 at " + level + ": replay and shadow run both passed, auto-promotes (EX-07)");
        return level == AutonomyLevel.L3_AUTONOMOUS
            ? Result.auto("T1 at learner autonomy L3: replay and shadow run passed, auto-promotes within the change budget")
            : Result.needsApproval("T1 auto-promotes only at learner autonomy L3 (currently " + level + ")");
    }

    // ------------------------------------------------------------------ manual

    /**
     * A person promoting an APPROVED proposal. Returns {@link Verdict#NEEDS_OVERRIDE} listing what they would waive when
     * the evidence or budget falls short and they gave no reason; with a reason of at least ten characters it allows the
     * promotion and the caller records what was waived.
     */
    public static Result evaluateManualPromote(Proposal p, Context ctx, String overrideReason) {
        Result hard = hardStops(p, ctx);
        if (hard != null) return hard;
        if (p.status() != ProposalStatus.APPROVED) return Result.blocked("proposal must be APPROVED before a person can promote it (it is " + p.status() + ")");
        if (p.tier() == PolicyChangeset.RiskTier.T3) return Result.blocked("T3 decisions are never autonomous, including by manual promotion of an auto-drafted change");

        List<String> concerns = concerns(p, ctx);
        if (concerns.isEmpty()) return Result.auto("approved by a person; evidence and budget checks passed");
        String reason = overrideReason == null ? "" : overrideReason.trim();
        if (reason.length() >= MIN_OVERRIDE_REASON) return new Result(Verdict.AUTO_PROMOTE, "promoted by a person who waived: " + String.join("; ", concerns), concerns);
        return new Result(Verdict.NEEDS_OVERRIDE, "Promoting this change needs a written reason, because " + String.join("; ", concerns), concerns);
    }

    /** What a person would be waiving by promoting this change now (empty when nothing). Also shown on the change card. */
    public static List<String> concerns(Proposal p, Context ctx) {
        List<String> out = new ArrayList<>();
        Map<String, Object> report = p.evaluationReport();
        String type = p.changeset().target() == null ? "" : p.changeset().target().type();
        Boolean shadowOk = shadowPassed(report);
        boolean hasInterval = report != null && report.get("confidenceInterval") instanceof Map<?, ?>;
        if (!hasInterval) {
            if ("decision".equals(type)) out.add("it has no replay evaluation on past decisions");
            else if (!Boolean.TRUE.equals(shadowOk)) out.add("this kind of change cannot be replayed and no shadow run has passed");
        } else if (Boolean.TRUE.equals(significantlyWorse(report))) {
            out.add("replay shows it makes outcomes worse");
        } else if (!Boolean.TRUE.equals(positiveLift(report))) {
            out.add("replay shows no clear improvement (the confidence interval includes zero)");
        }
        if (Boolean.FALSE.equals(shadowOk)) out.add("the shadow run did not pass");
        if (p.criticReview() != null && p.criticReview().verdict() == Review.Verdict.RISKY) out.add("the critic review flagged it as risky");
        String step = stepViolation(p.changeset(), ctx.learner());
        if (step != null) out.add(step);
        String budget = budgetViolation(p, ctx);
        if (budget != null) out.add(budget);
        return out;
    }

    // ------------------------------------------------------------------ shared checks

    /** Checks no reason can override. */
    private static Result hardStops(Proposal p, Context ctx) {
        if (isInsideAnyWindow(ctx.freezeWindows(), ctx.now())) return Result.blocked("inside a tenant freeze window");
        if (ctx.learningOffReason() != null) return Result.blocked("learning is switched off (" + ctx.learningOffReason().replace('_', ' ') + "); nothing is promoted until it is back on");
        LearnerState learner = ctx.learner();
        if (!learner.enabled()) return Result.blocked("learner '" + learner.learnerKey() + "' is kill-switched off");
        if (learner.paused()) return Result.blocked("learner '" + learner.learnerKey() + "' is paused: " + learner.pausedReason());
        String refused = refusedOutright(p.changeset());
        if (refused != null) return Result.blocked(refused);
        return null;
    }

    /** GV-02: the largest numeric move that exceeds its limit, described for a person; null when every move fits. */
    static String stepViolation(PolicyChangeset cs, LearnerState learner) {
        Double learnerLimit = learner == null ? null : learner.maxStep();
        for (PolicyChange c : cs.changes()) {
            double[] fromTo = numericFromTo(c);
            if (fromTo == null) continue;
            double step = Math.abs(fromTo[1] - fromTo[0]);
            boolean bounded = fromTo[0] >= 0 && fromTo[0] <= 1 && fromTo[1] >= 0 && fromTo[1] <= 1;
            Double limit = learnerLimit != null ? learnerLimit : bounded ? DEFAULT_MAX_STEP : null;
            if (limit != null && step > limit + 1e-9) {
                return String.format(Locale.ROOT, "it moves %s by %.4f, more than the max step of %.4f (GV-02)", label(c), step, limit);
            }
        }
        return null;
    }

    /** GV-02: at most {@code targetBudgetPerWeek} promotions of one target per week. */
    static String budgetViolation(Proposal p, Context ctx) {
        if (ctx.targetPromotionsThisWeek() < ctx.targetBudgetPerWeek()) return null;
        return String.format(Locale.ROOT, "%s was already promoted %d time%s in the last 7 days (budget %d per week, GV-02)",
            p.changeset().target(), ctx.targetPromotionsThisWeek(), ctx.targetPromotionsThisWeek() == 1 ? "" : "s", ctx.targetBudgetPerWeek());
    }

    static double[] numericFromTo(PolicyChange c) {
        return switch (c) {
            case PolicyChange.ThresholdChange tc -> new double[]{tc.from(), tc.to()};
            case PolicyChange.WeightChange wc -> new double[]{wc.from(), wc.to()};
            case PolicyChange.ParameterChange pc -> pc.from() instanceof Number f && pc.to() instanceof Number t ? new double[]{f.doubleValue(), t.doubleValue()} : null;
            default -> null;
        };
    }

    private static String label(PolicyChange c) {
        return switch (c) {
            case PolicyChange.ThresholdChange tc -> tc.decision() + " " + tc.band() + " threshold";
            case PolicyChange.WeightChange wc -> wc.target() + " " + wc.weightKey() + " weight";
            case PolicyChange.ParameterChange pc -> pc.parameter();
            default -> c.getClass().getSimpleName();
        };
    }

    /** Changes no approval can make: putting refunds or cancellations on an autonomous-action allow-list. */
    static String refusedOutright(PolicyChangeset cs) {
        for (PolicyChange c : cs.changes()) {
            if (c instanceof PolicyChange.AllowListChange a && a.add()) {
                String e = a.entry().toLowerCase(Locale.ROOT);
                if (e.contains("refund") || e.contains("cancel")) return "refunds and cancellations are never autonomous; '" + a.entry() + "' cannot be added to " + a.listName();
            }
        }
        return null;
    }

    /** Changes that may be promoted, but only by a human: refunds, cancellations, pricing, objective, autonomy. */
    static String neverAutonomous(PolicyChangeset cs) {
        List<String> touched = new ArrayList<>();
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
            String s = t == null ? "" : t.toLowerCase(Locale.ROOT);
            for (String w : NEVER_AUTONOMOUS) if (s.contains(w)) return "changes touching '" + t + "' are never autonomous and need a human approver";
        }
        return null;
    }

    /** {@code evaluationReport.shadow.passed}, written when a shadow experiment for the proposal completes. */
    static Boolean shadowPassed(Map<String, Object> report) {
        if (report == null || !(report.get("shadow") instanceof Map<?, ?> m)) return null;
        return m.get("passed") instanceof Boolean b ? b : null;
    }

    /** GV-07: any change whose parameter, rule or allow-list mentions a postal or ZIP/PIN code. */
    public static boolean isPostalCodeScoped(PolicyChangeset cs) {
        List<String> texts = new ArrayList<>();
        if (cs.target() != null) texts.add(cs.target().toString());
        for (PolicyChange c : cs.changes()) {
            switch (c) {
                case PolicyChange.ParameterChange pc -> texts.add(pc.parameter());
                case PolicyChange.RuleAdd ra -> texts.add(ra.expression());
                case PolicyChange.AllowListChange a -> texts.add(a.listName() + " " + a.entry());
                default -> { }
            }
        }
        for (String t : texts) {
            String s = t == null ? "" : t.toLowerCase(Locale.ROOT);
            for (String w : POSTAL_WORDS) if (s.contains(w)) return true;
        }
        return false;
    }

    /** True when the replay's confidence interval is entirely above zero; null with no interval to judge. */
    static Boolean positiveLift(Map<String, Object> report) {
        if (report == null) return null;
        if (report.get("confidenceInterval") instanceof Map<?, ?> m && m.get("lower") instanceof Number lower) return lower.doubleValue() > 0;
        return null;
    }

    /** True when the evaluation's confidence interval lies entirely below zero. */
    static Boolean significantlyWorse(Map<String, Object> report) {
        if (report == null) return null;
        if (report.get("confidenceInterval") instanceof Map<?, ?> m && m.get("upper") instanceof Number upper) return upper.doubleValue() < 0;
        return null;
    }

    static boolean isInsideAnyWindow(List<FreezeWindow> windows, Instant now) {
        if (windows == null || windows.isEmpty()) return false;
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        for (FreezeWindow w : windows) if (!today.isBefore(w.from()) && !today.isAfter(w.to())) return true;
        return false;
    }

    public record FreezeWindow(LocalDate from, LocalDate to) {}
}
