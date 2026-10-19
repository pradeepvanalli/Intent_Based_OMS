package ai.intentoms.learning.promotion;

import ai.intentoms.learning.model.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** EX-07 by the requirements doc's wording, GV-01/02/03/05/07, and the manual-promotion evidence and override rules. */
class PromotionGuardTest {
    private static Proposal proposal(PolicyChangeset.RiskTier tier, ProposalStatus status, Map<String, Object> evalReport, Review.Verdict verdict) {
        return proposal(new ArtifactRef("decision", "sourcing_node_score"), List.of(new PolicyChange.ThresholdChange("sourcing_node_score", "act", 0.80, 0.82)),
            tier, status, evalReport, verdict);
    }

    private static Proposal proposal(ArtifactRef target, List<PolicyChange> changes, PolicyChangeset.RiskTier tier, ProposalStatus status, Map<String, Object> evalReport,
                                     Review.Verdict verdict) {
        PolicyChangeset cs = new PolicyChangeset(target, 3, changes, "hyp_1", tier);
        return new Proposal("prop_1", cs, "hyp_1", tier, status, verdict == null ? null : new Review(verdict, List.of()),
            evalReport, "system:node_reliability", Instant.now(), Instant.now(), Instant.now(), null, null, null, null, null, null, null);
    }

    private static LearnerState learner(AutonomyLevel level, boolean enabled) {
        return new LearnerState("node_reliability", level, enabled, 5, 0.1, null, "system", Instant.now());
    }

    private static PromotionGuard.Context ctx(AutonomyLevel level) { return ctx(learner(level, true), null, 0, 1); }

    private static PromotionGuard.Context ctx(LearnerState l, String offReason, int promotedThisWeek, int budget) {
        return new PromotionGuard.Context(l, List.of(), Instant.now(), offReason, promotedThisWeek, budget);
    }

    private static Map<String, Object> positiveLift() { return Map.of("confidenceInterval", Map.of("lower", 0.01, "upper", 0.2), "liftEstimate", 0.1); }
    private static Map<String, Object> crossesZero() { return Map.of("confidenceInterval", Map.of("lower", -0.01, "upper", 0.12), "liftEstimate", 0.05); }
    private static Map<String, Object> negativeLift() { return Map.of("confidenceInterval", Map.of("lower", -0.2, "upper", -0.01), "liftEstimate", -0.1); }

    private static Map<String, Object> withShadow(Map<String, Object> report, boolean passed) {
        Map<String, Object> r = new HashMap<>(report);
        r.put("shadow", Map.of("passed", passed));
        return r;
    }

    // ---------------------------------------------------------------- automatic (EX-07 wording)

    @Test void t0PromotesAutomaticallyOnlyAfterReplayAndShadow() {
        for (AutonomyLevel level : List.of(AutonomyLevel.L1_RECOMMEND, AutonomyLevel.L2_ACT_WITH_APPROVAL, AutonomyLevel.L3_AUTONOMOUS)) {
            var replayOnly = proposal(PolicyChangeset.RiskTier.T0, ProposalStatus.EVALUATED, positiveLift(), Review.Verdict.LOOKS_SAFE);
            assertThat(PromotionGuard.evaluate(replayOnly, ctx(level)).verdict()).as(level + " replay only").isEqualTo(PromotionGuard.Verdict.NEEDS_APPROVAL);
            var both = proposal(PolicyChangeset.RiskTier.T0, ProposalStatus.EVALUATED, withShadow(positiveLift(), true), Review.Verdict.LOOKS_SAFE);
            assertThat(PromotionGuard.evaluate(both, ctx(level)).verdict()).as(level + " replay and shadow").isEqualTo(PromotionGuard.Verdict.AUTO_PROMOTE);
        }
    }

    @Test void aFailedShadowOrAReplayThatCrossesZeroHoldsT0ForAPerson() {
        var failedShadow = proposal(PolicyChangeset.RiskTier.T0, ProposalStatus.EVALUATED, withShadow(positiveLift(), false), Review.Verdict.LOOKS_SAFE);
        assertThat(PromotionGuard.evaluate(failedShadow, ctx(AutonomyLevel.L3_AUTONOMOUS)).verdict()).isEqualTo(PromotionGuard.Verdict.NEEDS_APPROVAL);
        var weak = proposal(PolicyChangeset.RiskTier.T0, ProposalStatus.EVALUATED, withShadow(crossesZero(), true), Review.Verdict.LOOKS_SAFE);
        assertThat(PromotionGuard.evaluate(weak, ctx(AutonomyLevel.L3_AUTONOMOUS)).verdict()).isEqualTo(PromotionGuard.Verdict.NEEDS_APPROVAL);
        var worse = proposal(PolicyChangeset.RiskTier.T0, ProposalStatus.EVALUATED, withShadow(negativeLift(), true), Review.Verdict.LOOKS_SAFE);
        assertThat(PromotionGuard.evaluate(worse, ctx(AutonomyLevel.L3_AUTONOMOUS)).verdict()).isEqualTo(PromotionGuard.Verdict.BLOCKED);
    }

    @Test void t1IsAutomaticOnlyAtL3() {
        var p = proposal(PolicyChangeset.RiskTier.T1, ProposalStatus.EVALUATED, withShadow(positiveLift(), true), Review.Verdict.LOOKS_SAFE);
        assertThat(PromotionGuard.evaluate(p, ctx(AutonomyLevel.L2_ACT_WITH_APPROVAL)).verdict()).isEqualTo(PromotionGuard.Verdict.NEEDS_APPROVAL);
        assertThat(PromotionGuard.evaluate(p, ctx(AutonomyLevel.L3_AUTONOMOUS)).verdict()).isEqualTo(PromotionGuard.Verdict.AUTO_PROMOTE);
    }

    @Test void t2AlwaysNeedsApprovalAndT3IsNeverAutonomous() {
        var t2 = proposal(PolicyChangeset.RiskTier.T2, ProposalStatus.EVALUATED, withShadow(positiveLift(), true), Review.Verdict.LOOKS_SAFE);
        assertThat(PromotionGuard.evaluate(t2, ctx(AutonomyLevel.L3_AUTONOMOUS)).verdict()).isEqualTo(PromotionGuard.Verdict.NEEDS_APPROVAL);
        var t3 = proposal(PolicyChangeset.RiskTier.T3, ProposalStatus.EVALUATED, withShadow(positiveLift(), true), Review.Verdict.LOOKS_SAFE);
        assertThat(PromotionGuard.evaluate(t3, ctx(AutonomyLevel.L3_AUTONOMOUS)).verdict()).isEqualTo(PromotionGuard.Verdict.BLOCKED);
    }

    @Test void l0NeverPromotes() {
        var p = proposal(PolicyChangeset.RiskTier.T0, ProposalStatus.EVALUATED, withShadow(positiveLift(), true), Review.Verdict.LOOKS_SAFE);
        assertThat(PromotionGuard.evaluate(p, ctx(AutonomyLevel.L0_OBSERVE)).verdict()).isEqualTo(PromotionGuard.Verdict.BLOCKED);
    }

    @Test void killSwitchesAndPausesBlockAutomaticAndManualPromotion() {
        var auto = proposal(PolicyChangeset.RiskTier.T0, ProposalStatus.EVALUATED, withShadow(positiveLift(), true), Review.Verdict.LOOKS_SAFE);
        var manual = proposal(PolicyChangeset.RiskTier.T1, ProposalStatus.APPROVED, withShadow(positiveLift(), true), Review.Verdict.LOOKS_SAFE);
        for (String off : List.of("tenant_kill_switch", "global_kill_switch")) {
            var c = ctx(learner(AutonomyLevel.L3_AUTONOMOUS, true), off, 0, 1);
            assertThat(PromotionGuard.evaluate(auto, c).verdict()).as(off).isEqualTo(PromotionGuard.Verdict.BLOCKED);
            assertThat(PromotionGuard.evaluateManualPromote(manual, c, "an override reason does not lift a kill switch").verdict()).as(off).isEqualTo(PromotionGuard.Verdict.BLOCKED);
        }
        var switchedOff = ctx(learner(AutonomyLevel.L3_AUTONOMOUS, false), null, 0, 1);
        assertThat(PromotionGuard.evaluate(auto, switchedOff).verdict()).isEqualTo(PromotionGuard.Verdict.BLOCKED);
        var paused = ctx(new LearnerState("node_reliability", AutonomyLevel.L3_AUTONOMOUS, true, 5, 0.1, null, "system", Instant.now(), "coverage below 80%"), null, 0, 1);
        assertThat(PromotionGuard.evaluate(auto, paused).verdict()).isEqualTo(PromotionGuard.Verdict.BLOCKED);
    }

    @Test void freezeWindowBlocksEvenAnOverride() {
        var p = proposal(PolicyChangeset.RiskTier.T0, ProposalStatus.APPROVED, withShadow(positiveLift(), true), Review.Verdict.LOOKS_SAFE);
        var window = new PromotionGuard.FreezeWindow(LocalDate.now().minusDays(1), LocalDate.now().plusDays(1));
        var c = new PromotionGuard.Context(learner(AutonomyLevel.L3_AUTONOMOUS, true), List.of(window), Instant.now(), null, 0, 1);
        assertThat(PromotionGuard.evaluate(p, c).verdict()).isEqualTo(PromotionGuard.Verdict.BLOCKED);
        assertThat(PromotionGuard.evaluateManualPromote(p, c, "it is urgent, please let it through").verdict()).isEqualTo(PromotionGuard.Verdict.BLOCKED);
    }

    @Test void riskyCriticReviewHoldsT0ForAPerson() {
        var p = proposal(PolicyChangeset.RiskTier.T0, ProposalStatus.EVALUATED, withShadow(positiveLift(), true), Review.Verdict.RISKY);
        assertThat(PromotionGuard.evaluate(p, ctx(AutonomyLevel.L3_AUTONOMOUS)).verdict()).isEqualTo(PromotionGuard.Verdict.NEEDS_APPROVAL);
    }

    // ---------------------------------------------------------------- GV-02 budgets and steps

    @Test void aStepAboveTheLimitIsHeldNotClipped() {
        var big = proposal(new ArtifactRef("decision", "sourcing_node_score"), List.of(new PolicyChange.ThresholdChange("sourcing_node_score", "act", 0.80, 0.95)),
            PolicyChangeset.RiskTier.T0, ProposalStatus.EVALUATED, withShadow(positiveLift(), true), Review.Verdict.LOOKS_SAFE);
        assertThat(PromotionGuard.evaluate(big, ctx(AutonomyLevel.L3_AUTONOMOUS)).verdict()).isEqualTo(PromotionGuard.Verdict.NEEDS_APPROVAL);
        // With no learner limit the default 0.05 applies to bounded values; a 0.06 move is held, 0.04 is fine.
        LearnerState noLimit = new LearnerState("node_reliability", AutonomyLevel.L3_AUTONOMOUS, true, 5, null, null, "system", Instant.now());
        var six = proposal(new ArtifactRef("decision", "x"), List.of(new PolicyChange.ThresholdChange("x", "act", 0.80, 0.86)),
            PolicyChangeset.RiskTier.T0, ProposalStatus.EVALUATED, withShadow(positiveLift(), true), Review.Verdict.LOOKS_SAFE);
        var four = proposal(new ArtifactRef("decision", "x"), List.of(new PolicyChange.ThresholdChange("x", "act", 0.80, 0.84)),
            PolicyChangeset.RiskTier.T0, ProposalStatus.EVALUATED, withShadow(positiveLift(), true), Review.Verdict.LOOKS_SAFE);
        assertThat(PromotionGuard.evaluate(six, ctx(noLimit, null, 0, 1)).verdict()).isEqualTo(PromotionGuard.Verdict.NEEDS_APPROVAL);
        assertThat(PromotionGuard.evaluate(four, ctx(noLimit, null, 0, 1)).verdict()).isEqualTo(PromotionGuard.Verdict.AUTO_PROMOTE);
    }

    @Test void theWeeklyBudgetIsPerTargetAndCountsEveryPromotion() {
        var p = proposal(PolicyChangeset.RiskTier.T0, ProposalStatus.EVALUATED, withShadow(positiveLift(), true), Review.Verdict.LOOKS_SAFE);
        var spent = ctx(learner(AutonomyLevel.L3_AUTONOMOUS, true), null, 1, 1);
        assertThat(PromotionGuard.evaluate(p, spent).verdict()).isEqualTo(PromotionGuard.Verdict.NEEDS_APPROVAL);
        assertThat(PromotionGuard.evaluate(p, spent).reason()).contains("GV-02");
    }

    // ---------------------------------------------------------------- manual promotion (defect 28)

    @Test void aPersonNeedsEvidenceOrAWrittenReason() {
        var noEvidence = proposal(PolicyChangeset.RiskTier.T1, ProposalStatus.APPROVED, Map.of(), Review.Verdict.LOOKS_SAFE);
        var r = PromotionGuard.evaluateManualPromote(noEvidence, ctx(AutonomyLevel.L1_RECOMMEND), null);
        assertThat(r.verdict()).isEqualTo(PromotionGuard.Verdict.NEEDS_OVERRIDE);
        assertThat(r.waivable()).anyMatch(w -> w.contains("no replay"));
        assertThat(PromotionGuard.evaluateManualPromote(noEvidence, ctx(AutonomyLevel.L1_RECOMMEND), "too short").verdict()).isEqualTo(PromotionGuard.Verdict.NEEDS_OVERRIDE);
        var ok = PromotionGuard.evaluateManualPromote(noEvidence, ctx(AutonomyLevel.L1_RECOMMEND), "Supplier contract requires this threshold from Monday");
        assertThat(ok.verdict()).isEqualTo(PromotionGuard.Verdict.AUTO_PROMOTE);
        assertThat(ok.waivable()).isNotEmpty();
    }

    @Test void aReplayThatCrossesZeroOrIsWorseNeedsAReason() {
        for (Map<String, Object> report : List.of(crossesZero(), negativeLift())) {
            var p = proposal(PolicyChangeset.RiskTier.T1, ProposalStatus.APPROVED, report, Review.Verdict.LOOKS_SAFE);
            assertThat(PromotionGuard.evaluateManualPromote(p, ctx(AutonomyLevel.L1_RECOMMEND), null).verdict()).isEqualTo(PromotionGuard.Verdict.NEEDS_OVERRIDE);
        }
        var good = proposal(PolicyChangeset.RiskTier.T1, ProposalStatus.APPROVED, positiveLift(), Review.Verdict.LOOKS_SAFE);
        var r = PromotionGuard.evaluateManualPromote(good, ctx(AutonomyLevel.L1_RECOMMEND), null);
        assertThat(r.verdict()).isEqualTo(PromotionGuard.Verdict.AUTO_PROMOTE);
        assertThat(r.waivable()).isEmpty();
    }

    @Test void aLearnedValueNeedsAPassedShadowRunBecauseItCannotBeReplayed() {
        ArtifactRef live = new ArtifactRef("parameter", "live:sourcing.node_reject_rate");
        List<PolicyChange> change = List.of(new PolicyChange.ParameterChange("live:sourcing.node_reject_rate", Map.of("enabled", false), Map.of("enabled", true)));
        var noCounterfactual = proposal(live, change, PolicyChangeset.RiskTier.T1, ProposalStatus.APPROVED, Map.of("method", "no_counterfactual"), null);
        assertThat(PromotionGuard.evaluateManualPromote(noCounterfactual, ctx(AutonomyLevel.L1_RECOMMEND), null).verdict()).isEqualTo(PromotionGuard.Verdict.NEEDS_OVERRIDE);
        var shadowed = proposal(live, change, PolicyChangeset.RiskTier.T1, ProposalStatus.APPROVED, Map.of("method", "no_counterfactual", "shadow", Map.of("passed", true)), null);
        assertThat(PromotionGuard.evaluateManualPromote(shadowed, ctx(AutonomyLevel.L1_RECOMMEND), null).verdict()).isEqualTo(PromotionGuard.Verdict.AUTO_PROMOTE);
    }

    @Test void overBudgetManualPromotionNeedsAReason() {
        var p = proposal(PolicyChangeset.RiskTier.T1, ProposalStatus.APPROVED, positiveLift(), Review.Verdict.LOOKS_SAFE);
        var r = PromotionGuard.evaluateManualPromote(p, ctx(learner(AutonomyLevel.L1_RECOMMEND, true), null, 1, 1), null);
        assertThat(r.verdict()).isEqualTo(PromotionGuard.Verdict.NEEDS_OVERRIDE);
        assertThat(r.waivable()).anyMatch(w -> w.contains("GV-02"));
    }

    @Test void onlyAnApprovedProposalCanBePromotedByAPersonAndT3Never() {
        var evaluated = proposal(PolicyChangeset.RiskTier.T1, ProposalStatus.EVALUATED, positiveLift(), Review.Verdict.LOOKS_SAFE);
        assertThat(PromotionGuard.evaluateManualPromote(evaluated, ctx(AutonomyLevel.L3_AUTONOMOUS), null).verdict()).isEqualTo(PromotionGuard.Verdict.BLOCKED);
        var t3 = proposal(PolicyChangeset.RiskTier.T3, ProposalStatus.APPROVED, positiveLift(), Review.Verdict.LOOKS_SAFE);
        assertThat(PromotionGuard.evaluateManualPromote(t3, ctx(AutonomyLevel.L3_AUTONOMOUS), "a written reason that is long enough").verdict()).isEqualTo(PromotionGuard.Verdict.BLOCKED);
    }

    // ---------------------------------------------------------------- never-autonomous and GV-07

    @Test void refundAndPricingChangesAreNeverAutonomous() {
        var pricing = proposal(new ArtifactRef("parameter", "pricing.markdown_cap"), List.of(new PolicyChange.ParameterChange("pricing.markdown_cap", 0.1, 0.12)),
            PolicyChangeset.RiskTier.T0, ProposalStatus.EVALUATED, withShadow(positiveLift(), true), Review.Verdict.LOOKS_SAFE);
        assertThat(PromotionGuard.evaluate(pricing, ctx(AutonomyLevel.L3_AUTONOMOUS)).verdict()).isEqualTo(PromotionGuard.Verdict.NEEDS_APPROVAL);

        var refund = proposal(new ArtifactRef("config", "autonomous_actions"), List.of(new PolicyChange.AllowListChange("autonomous_actions", "refund", true)),
            PolicyChangeset.RiskTier.T0, ProposalStatus.APPROVED, withShadow(positiveLift(), true), Review.Verdict.LOOKS_SAFE);
        assertThat(PromotionGuard.evaluateManualPromote(refund, ctx(AutonomyLevel.L3_AUTONOMOUS), "a written reason that is long enough").verdict())
            .isEqualTo(PromotionGuard.Verdict.BLOCKED);
    }

    @Test void postalScopesAreRecognisedByEveryCommonName() {
        for (String parameter : List.of("sourcing.blocked_zip3", "fraud.postal_hold", "cod.blocked_postcodes", "cod.pincode_denylist")) {
            var cs = new PolicyChangeset(new ArtifactRef("config", parameter), 0, List.of(new PolicyChange.ParameterChange(parameter, List.of(), List.of("x"))), null, PolicyChangeset.RiskTier.T1);
            assertThat(PromotionGuard.isPostalCodeScoped(cs)).as(parameter).isTrue();
        }
    }
}
