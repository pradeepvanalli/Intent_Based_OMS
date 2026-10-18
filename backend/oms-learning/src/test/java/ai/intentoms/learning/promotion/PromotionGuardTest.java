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

    @Test void t0AutoPromotesWithPositiveLift() {
        var p = proposal(PolicyChangeset.RiskTier.T0, ProposalStatus.EVALUATED, positiveLift(), Review.Verdict.LOOKS_SAFE);
        var r = PromotionGuard.evaluate(p, learner(AutonomyLevel.L1_RECOMMEND, true), List.of(), Instant.now(), 0);
        assertThat(r.verdict()).isEqualTo(PromotionGuard.Verdict.AUTO_PROMOTE);
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
