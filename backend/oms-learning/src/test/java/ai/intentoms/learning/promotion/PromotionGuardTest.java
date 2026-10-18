package ai.intentoms.learning.promotion;

import ai.intentoms.learning.model.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PromotionGuardTest {
    private static Proposal proposal(PolicyChangeset.RiskTier tier, ProposalStatus status, Map<String, Object> evalReport, Review.Verdict verdict) {
        PolicyChangeset cs = new PolicyChangeset(new ArtifactRef("decision", "sourcing_node_score"), 3,
            List.of(new PolicyChange.ThresholdChange("sourcing_node_score", "act", 0.80, 0.82)), "hyp_1", tier);
        return new Proposal("prop_1", cs, "hyp_1", tier, status, verdict == null ? null : new Review(verdict, List.of()),
            evalReport, "node_reliability", Instant.now(), Instant.now(), Instant.now(), null, null, null, null, null, null, null);
    }

    private static LearnerState learner(AutonomyLevel level, boolean enabled) {
        return new LearnerState("node_reliability", level, enabled, 5, 0.1, null, "system", Instant.now());
    }

    private static Map<String, Object> positiveLift() { return Map.of("confidenceInterval", Map.of("lower", 0.01, "upper", 0.2), "liftEstimate", 0.1); }
    private static Map<String, Object> negativeLift() { return Map.of("confidenceInterval", Map.of("lower", -0.2, "upper", -0.01), "liftEstimate", -0.1); }

    @Test void t0AutoPromotesAtL2WithPositiveLift() {
        var p = proposal(PolicyChangeset.RiskTier.T0, ProposalStatus.EVALUATED, positiveLift(), Review.Verdict.LOOKS_SAFE);
        var r = PromotionGuard.evaluate(p, learner(AutonomyLevel.L2_ACT_WITH_APPROVAL, true), List.of(), Instant.now(), 0);
        assertThat(r.verdict()).isEqualTo(PromotionGuard.Verdict.AUTO_PROMOTE);
    }

    @Test void t0AtL1NeedsShadowBeforeAutoPromoting() {
        var p = proposal(PolicyChangeset.RiskTier.T0, ProposalStatus.EVALUATED, positiveLift(), Review.Verdict.LOOKS_SAFE);
        assertThat(PromotionGuard.evaluate(p, learner(AutonomyLevel.L1_RECOMMEND, true), List.of(), Instant.now(), 0).verdict())
            .isEqualTo(PromotionGuard.Verdict.NEEDS_APPROVAL);
        var withShadow = new java.util.HashMap<String, Object>(positiveLift());
        withShadow.put("shadow", Map.of("passed", true));
        var p2 = proposal(PolicyChangeset.RiskTier.T0, ProposalStatus.EVALUATED, withShadow, Review.Verdict.LOOKS_SAFE);
        assertThat(PromotionGuard.evaluate(p2, learner(AutonomyLevel.L1_RECOMMEND, true), List.of(), Instant.now(), 0).verdict())
            .isEqualTo(PromotionGuard.Verdict.AUTO_PROMOTE);
    }

    @Test void l0NeverPromotes() {
        var p = proposal(PolicyChangeset.RiskTier.T0, ProposalStatus.EVALUATED, positiveLift(), Review.Verdict.LOOKS_SAFE);
        var r = PromotionGuard.evaluate(p, learner(AutonomyLevel.L0_OBSERVE, true), List.of(), Instant.now(), 0);
        assertThat(r.verdict()).isEqualTo(PromotionGuard.Verdict.BLOCKED);
    }

    @Test void pausedLearnerNeverPromotes() {
        var p = proposal(PolicyChangeset.RiskTier.T0, ProposalStatus.EVALUATED, positiveLift(), Review.Verdict.LOOKS_SAFE);
        var paused = new LearnerState("node_reliability", AutonomyLevel.L3_AUTONOMOUS, true, 5, 0.1, null, "system", Instant.now(), "coverage below 80%");
        assertThat(PromotionGuard.evaluate(p, paused, List.of(), Instant.now(), 0).verdict()).isEqualTo(PromotionGuard.Verdict.BLOCKED);
    }

    @Test void refundAndPricingChangesAreNeverAutonomous() {
        PolicyChangeset cs = new PolicyChangeset(new ArtifactRef("parameter", "pricing.markdown_cap"), 1,
            List.of(new PolicyChange.ParameterChange("pricing.markdown_cap", 0.1, 0.12)), "hyp_1", PolicyChangeset.RiskTier.T0);
        Proposal p = new Proposal("prop_3", cs, "hyp_1", PolicyChangeset.RiskTier.T0, ProposalStatus.EVALUATED, new Review(Review.Verdict.LOOKS_SAFE, List.of()),
            positiveLift(), "node_reliability", Instant.now(), Instant.now(), Instant.now(), null, null, null, null, null, null, null);
        assertThat(PromotionGuard.evaluate(p, learner(AutonomyLevel.L3_AUTONOMOUS, true), List.of(), Instant.now(), 0).verdict())
            .isEqualTo(PromotionGuard.Verdict.NEEDS_APPROVAL);

        PolicyChangeset refund = new PolicyChangeset(new ArtifactRef("parameter", "autonomous_actions"), 1,
            List.of(new PolicyChange.AllowListChange("autonomous_actions", "refund", true)), "hyp_1", PolicyChangeset.RiskTier.T0);
        Proposal p2 = new Proposal("prop_4", refund, "hyp_1", PolicyChangeset.RiskTier.T0, ProposalStatus.APPROVED, new Review(Review.Verdict.LOOKS_SAFE, List.of()),
            positiveLift(), "node_reliability", Instant.now(), Instant.now(), Instant.now(), null, null, null, null, null, null, null);
        assertThat(PromotionGuard.evaluateManualPromote(p2, learner(AutonomyLevel.L3_AUTONOMOUS, true), List.of(), Instant.now()).verdict())
            .isEqualTo(PromotionGuard.Verdict.BLOCKED);
    }

    @Test void aPersonMayPromoteAnApprovedNoOpButNotAChangeShownToBeWorse() {
        Map<String, Object> zero = Map.of("liftEstimate", 0.0, "confidenceInterval", Map.of("lower", 0.0, "upper", 0.0));
        var noEvidence = proposal(PolicyChangeset.RiskTier.T1, ProposalStatus.APPROVED, zero, Review.Verdict.LOOKS_SAFE);
        assertThat(PromotionGuard.evaluateManualPromote(noEvidence, learner(AutonomyLevel.L1_RECOMMEND, true), List.of(), Instant.now()).verdict())
            .isEqualTo(PromotionGuard.Verdict.AUTO_PROMOTE);
        var auto = proposal(PolicyChangeset.RiskTier.T0, ProposalStatus.EVALUATED, zero, Review.Verdict.LOOKS_SAFE);
        assertThat(PromotionGuard.evaluate(auto, learner(AutonomyLevel.L3_AUTONOMOUS, true), List.of(), Instant.now(), 0).verdict())
            .isEqualTo(PromotionGuard.Verdict.BLOCKED);
        var worse = proposal(PolicyChangeset.RiskTier.T1, ProposalStatus.APPROVED, negativeLift(), Review.Verdict.LOOKS_SAFE);
        assertThat(PromotionGuard.evaluateManualPromote(worse, learner(AutonomyLevel.L1_RECOMMEND, true), List.of(), Instant.now()).verdict())
            .isEqualTo(PromotionGuard.Verdict.BLOCKED);
    }

    @Test void t0DoesNotPromoteOnNegativeLift() {
        var p = proposal(PolicyChangeset.RiskTier.T0, ProposalStatus.EVALUATED, negativeLift(), Review.Verdict.LOOKS_SAFE);
        var r = PromotionGuard.evaluate(p, learner(AutonomyLevel.L3_AUTONOMOUS, true), List.of(), Instant.now(), 0);
        assertThat(r.verdict()).isEqualTo(PromotionGuard.Verdict.BLOCKED);
    }

    @Test void t1NeedsApprovalBelowL3() {
        var p = proposal(PolicyChangeset.RiskTier.T1, ProposalStatus.EVALUATED, positiveLift(), Review.Verdict.LOOKS_SAFE);
        var r = PromotionGuard.evaluate(p, learner(AutonomyLevel.L2_ACT_WITH_APPROVAL, true), List.of(), Instant.now(), 0);
        assertThat(r.verdict()).isEqualTo(PromotionGuard.Verdict.NEEDS_APPROVAL);
    }

    @Test void t1AutoPromotesAtL3() {
        var p = proposal(PolicyChangeset.RiskTier.T1, ProposalStatus.EVALUATED, positiveLift(), Review.Verdict.LOOKS_SAFE);
        var r = PromotionGuard.evaluate(p, learner(AutonomyLevel.L3_AUTONOMOUS, true), List.of(), Instant.now(), 0);
        assertThat(r.verdict()).isEqualTo(PromotionGuard.Verdict.AUTO_PROMOTE);
    }

    @Test void t2AlwaysNeedsApproval() {
        var p = proposal(PolicyChangeset.RiskTier.T2, ProposalStatus.EVALUATED, positiveLift(), Review.Verdict.LOOKS_SAFE);
        var r = PromotionGuard.evaluate(p, learner(AutonomyLevel.L3_AUTONOMOUS, true), List.of(), Instant.now(), 0);
        assertThat(r.verdict()).isEqualTo(PromotionGuard.Verdict.NEEDS_APPROVAL);
    }

    @Test void t3NeverAutonomous() {
        var p = proposal(PolicyChangeset.RiskTier.T3, ProposalStatus.EVALUATED, positiveLift(), Review.Verdict.LOOKS_SAFE);
        var r = PromotionGuard.evaluate(p, learner(AutonomyLevel.L3_AUTONOMOUS, true), List.of(), Instant.now(), 0);
        assertThat(r.verdict()).isEqualTo(PromotionGuard.Verdict.BLOCKED);
    }

    @Test void killSwitchedLearnerBlocksRegardlessOfTier() {
        var p = proposal(PolicyChangeset.RiskTier.T0, ProposalStatus.EVALUATED, positiveLift(), Review.Verdict.LOOKS_SAFE);
        var r = PromotionGuard.evaluate(p, learner(AutonomyLevel.L3_AUTONOMOUS, false), List.of(), Instant.now(), 0);
        assertThat(r.verdict()).isEqualTo(PromotionGuard.Verdict.BLOCKED);
    }

    @Test void freezeWindowBlocksEvenT0() {
        var p = proposal(PolicyChangeset.RiskTier.T0, ProposalStatus.EVALUATED, positiveLift(), Review.Verdict.LOOKS_SAFE);
        var window = new PromotionGuard.FreezeWindow(java.time.LocalDate.now().minusDays(1), java.time.LocalDate.now().plusDays(1));
        var r = PromotionGuard.evaluate(p, learner(AutonomyLevel.L3_AUTONOMOUS, true), List.of(window), Instant.now(), 0);
        assertThat(r.verdict()).isEqualTo(PromotionGuard.Verdict.BLOCKED);
    }

    @Test void stepExceedingMaxStepNeedsApproval() {
        PolicyChangeset cs = new PolicyChangeset(new ArtifactRef("decision", "sourcing_node_score"), 3,
            List.of(new PolicyChange.ThresholdChange("sourcing_node_score", "act", 0.80, 0.95)), "hyp_1", PolicyChangeset.RiskTier.T0);
        Proposal p = new Proposal("prop_2", cs, "hyp_1", PolicyChangeset.RiskTier.T0, ProposalStatus.EVALUATED, new Review(Review.Verdict.LOOKS_SAFE, List.of()),
            positiveLift(), "node_reliability", Instant.now(), Instant.now(), Instant.now(), null, null, null, null, null, null, null);
        var r = PromotionGuard.evaluate(p, learner(AutonomyLevel.L3_AUTONOMOUS, true), List.of(), Instant.now(), 0);
        assertThat(r.verdict()).isEqualTo(PromotionGuard.Verdict.NEEDS_APPROVAL);
    }

    @Test void changeBudgetExhaustedNeedsApproval() {
        var p = proposal(PolicyChangeset.RiskTier.T0, ProposalStatus.EVALUATED, positiveLift(), Review.Verdict.LOOKS_SAFE);
        var r = PromotionGuard.evaluate(p, learner(AutonomyLevel.L3_AUTONOMOUS, true), List.of(), Instant.now(), 5);
        assertThat(r.verdict()).isEqualTo(PromotionGuard.Verdict.NEEDS_APPROVAL);
    }

    @Test void riskyCriticReviewNeedsApprovalEvenAtT0() {
        var p = proposal(PolicyChangeset.RiskTier.T0, ProposalStatus.EVALUATED, positiveLift(), Review.Verdict.RISKY);
        var r = PromotionGuard.evaluate(p, learner(AutonomyLevel.L3_AUTONOMOUS, true), List.of(), Instant.now(), 0);
        assertThat(r.verdict()).isEqualTo(PromotionGuard.Verdict.NEEDS_APPROVAL);
    }
}
