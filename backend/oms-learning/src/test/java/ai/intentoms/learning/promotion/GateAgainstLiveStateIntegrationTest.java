package ai.intentoms.learning.promotion;

import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.learning.api.LiveUseController;
import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.learning.governance.ParameterResolver;
import ai.intentoms.learning.model.ArtifactRef;
import ai.intentoms.learning.model.AutonomyLevel;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.PolicyChangeset;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.ProposalStatus;
import ai.intentoms.learning.support.DecisionFabricTestBase;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.security.Principal;
import ai.intentoms.platform.security.PrincipalAuthentication;
import ai.intentoms.platform.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Audit 3 workstream D, through the real promotion service: D-2 (a change is judged against the live artifact, so a
 * proposal drafted before a rollback cannot move the live threshold twice the max step), D-3 (live use of a family that
 * feeds a T2 decision is T2 and never auto-promotes, even when it was stored as T1) and D-4 (the target's owner governs,
 * and a paused or switched-off owner or drafter blocks the change). Each test runs in a tenant of its own.
 */
class GateAgainstLiveStateIntegrationTest extends DecisionFabricTestBase {
    @Autowired PromotionService promotion;
    @Autowired LearnerGovernance governance;
    @Autowired ProposalOwnership ownership;
    @Autowired LiveUseController liveUse;
    @Autowired ParameterResolver parameters;

    private static final Actor HUMAN = Actor.parse("user:approver");
    private static final String REASON = "test: a written reason long enough to waive the soft checks";

    private void ownTenant(String prefix) {
        String tenant = prefix + "_" + Ids.shortId().toLowerCase();
        jdbc.sql("insert into tenant(tenant_id, name) values (:t, :t) on conflict do nothing").param("t", tenant).update();
        TenantContext.set(tenant);
        SecurityContextHolder.getContext().setAuthentication(new PrincipalAuthentication(
            new Principal(Principal.Type.USER, "tester", tenant, Set.of("admin"), Set.of("*"), 3)));
    }

    private double liveAct(String name) { return live(name).bands().actGte(); }

    private Map<String, Object> autoEvidence() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("liftEstimate", 0.04);
        r.put("confidenceInterval", Map.of("lower", 0.01, "upper", 0.07));
        r.put("shadow", Map.of("passed", true));
        return r;
    }

    // ------------------------------------------------------------------ D-2

    @Test
    void aProposalDraftedBeforeARollbackCannotMoveTheLiveThresholdTwiceTheMaxStep() {
        ownTenant("d2");
        String name = installProbe("d2_stale");
        int v1 = liveVersion(name);
        assertThat(liveAct(name)).isEqualTo(0.80);
        governance.setBudget("band_threshold", null, 10, "user:admin"); // only the staleness rule may stop what follows

        // The audit's sequence: X (0.80 -> 0.75) goes live, the band tuner drafts Y (0.75 -> 0.70) against it, X is rolled back.
        Proposal x = proposal("system:band_threshold", new ArtifactRef("decision", name), v1, PolicyChangeset.RiskTier.T1, ProposalStatus.APPROVED,
            new PolicyChange.ThresholdChange(name, "act", 0.80, 0.75));
        promotion.promote(x.proposalId(), HUMAN, REASON);
        int v2 = liveVersion(name);
        assertThat(liveAct(name)).isEqualTo(0.75);
        Proposal y = proposal("system:band_threshold", new ArtifactRef("decision", name), v2, PolicyChangeset.RiskTier.T1, ProposalStatus.APPROVED,
            new PolicyChange.ThresholdChange(name, "act", 0.75, 0.70));
        assertThat(promotion.promotionBlocker(y)).isNull(); // current while X is live
        promotion.rollback(x.proposalId(), HUMAN, "test: X is rolled back");
        assertThat(liveAct(name)).isEqualTo(0.80);

        // Manual path: refused (409) whatever the reason, and the live threshold does not move.
        assertThat(promotion.promotionBlocker(proposals.get(y.proposalId()))).contains("re-draft against the live version");
        assertThatThrownBy(() -> promotion.promote(y.proposalId(), HUMAN, REASON))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("drafted against version " + v2)
            .hasMessageContaining("re-draft against the live version");
        assertThat(liveAct(name)).isEqualTo(0.80);
        assertThat(proposals.get(y.proposalId()).status()).isEqualTo(ProposalStatus.APPROVED);

        // Automatic path: a stale T0 change with full evidence at L3 is not promoted either.
        governance.setAutonomy("band_threshold", AutonomyLevel.L3_AUTONOMOUS, "user:admin");
        Proposal z = proposal("system:band_threshold", new ArtifactRef("decision", name), v2, PolicyChangeset.RiskTier.T0, ProposalStatus.EVALUATED,
            new PolicyChange.ThresholdChange(name, "act", 0.75, 0.73));
        proposals.markEvaluated(z.proposalId(), autoEvidence(), Instant.now());
        assertThat(promotion.sweepOnce()).isZero();
        assertThat(proposals.get(z.proposalId()).status()).isEqualTo(ProposalStatus.EVALUATED);
        assertThat(liveAct(name)).isEqualTo(0.80);

        // A change drafted against the live version still goes through the automatic path, within the max step.
        DecisionDefinition now = live(name);
        Proposal fresh = proposal("system:band_threshold", new ArtifactRef("decision", name), now.version(), PolicyChangeset.RiskTier.T0, ProposalStatus.EVALUATED,
            new PolicyChange.ThresholdChange(name, "act", 0.80, 0.77));
        proposals.markEvaluated(fresh.proposalId(), autoEvidence(), Instant.now());
        assertThat(promotion.sweepOnce()).isEqualTo(1);
        assertThat(proposals.get(fresh.proposalId()).status()).isEqualTo(ProposalStatus.PROMOTED);
        assertThat(liveAct(name)).isEqualTo(0.77);
    }

    // ------------------------------------------------------------------ D-3

    private void installDecision(String name, String tier) {
        DecisionDefinition d = studio.createDraft(name, probeYaml(name, null).replace("tier: T1", "tier: " + tier));
        studio.publish(name, d.version(), "live", true);
    }

    @Test
    void liveUseOfAFamilyFeedingAT2DecisionIsT2AndNeverPromotesItself() {
        ownTenant("d3");
        installDecision("payment_risk", "T2");
        governance.setAutonomy("fraud_signals", AutonomyLevel.L3_AUTONOMOUS, "user:admin");
        governance.setBudget("fraud_signals", null, 10, "user:admin");

        // Drafted the way a person does it: the tier comes from payment_risk, not a hard-coded T1.
        Proposal p = liveUse.propose(new LiveUseController.LiveUseRequest("fraud.signal_risk", "fraud_signals", 0.0, 1.0, 0.1, true, "test D-3"));
        assertThat(p.tier()).isEqualTo(PolicyChangeset.RiskTier.T2);
        assertThat(p.changeset().tier()).isEqualTo(PolicyChangeset.RiskTier.T2);
        proposals.markEvaluated(p.proposalId(), autoEvidence(), Instant.now());
        assertThat(promotion.sweepOnce()).isZero();
        assertThat(proposals.get(p.proposalId()).status()).isEqualTo(ProposalStatus.EVALUATED);
        assertThat(parameters.isLive("fraud.signal_risk")).isFalse();

        // One stored as T1 before the fix is gated as T2 all the same.
        Proposal legacy = proposal("user:alice", new ArtifactRef("parameter", "live:fraud.address_reuse_count"), 0, PolicyChangeset.RiskTier.T1, ProposalStatus.EVALUATED,
            new PolicyChange.ParameterChange("live:fraud.address_reuse_count", Map.of("enabled", false),
                Map.of("enabled", true, "learner", "fraud_abuse_scanner", "min", 0.0, "max", 50.0, "maxStep", 5.0)));
        proposals.markEvaluated(legacy.proposalId(), autoEvidence(), Instant.now());
        governance.setAutonomy("fraud_abuse_scanner", AutonomyLevel.L3_AUTONOMOUS, "user:admin");
        governance.setBudget("fraud_abuse_scanner", null, 10, "user:admin");
        assertThat(promotion.sweepOnce()).isZero();
        assertThat(proposals.get(legacy.proposalId()).status()).isEqualTo(ProposalStatus.EVALUATED);

        // A person can still approve and promote it (T2 = approval, not never).
        proposals.approve(p.proposalId(), "user:reviewer", Instant.now());
        promotion.promote(p.proposalId(), HUMAN);
        assertThat(parameters.isLive("fraud.signal_risk")).isTrue();

        // A family that feeds only T1 decisions keeps the T1 path: automatic at L3.
        installDecision("sourcing_node_score", "T1");
        governance.setAutonomy("node_reliability", AutonomyLevel.L3_AUTONOMOUS, "user:admin");
        governance.setBudget("node_reliability", null, 10, "user:admin");
        Proposal t1 = liveUse.propose(new LiveUseController.LiveUseRequest("sourcing.node_reject_rate", "node_reliability", 0.0, 0.5, 0.1, true, "test D-3 control"));
        assertThat(t1.tier()).isEqualTo(PolicyChangeset.RiskTier.T1);
        proposals.markEvaluated(t1.proposalId(), autoEvidence(), Instant.now());
        assertThat(promotion.sweepOnce()).isEqualTo(1);
        assertThat(proposals.get(t1.proposalId()).status()).isEqualTo(ProposalStatus.PROMOTED);
    }

    // ------------------------------------------------------------------ D-4

    @Test
    void thePausedOwnerOfTheTargetBlocksAnotherLearnersDraftAndTheDrafterStillCounts() {
        ownTenant("d4");
        installDecision("return_risk", "T2");
        Proposal p = proposal("system:demo-seed.band_threshold", new ArtifactRef("decision", "return_risk"), liveVersion("return_risk"), PolicyChangeset.RiskTier.T2,
            ProposalStatus.APPROVED, new PolicyChange.ThresholdChange("return_risk", "act", 0.80, 0.83));
        assertThat(ownership.owner(p)).contains("fraud_threshold_tuner");
        assertThat(ownership.governors(p)).containsExactly("fraud_threshold_tuner", "band_threshold");
        assertThat(promotion.governingLearner(p)).isEqualTo("fraud_threshold_tuner");

        // The coverage monitor pauses the fraud tuner: the band tuner's draft of a fraud threshold is blocked too.
        governance.pause("fraud_threshold_tuner", "outcome coverage for address_risk is 53% (floor 80%)", "system:coverage-monitor");
        assertThat(promotion.promotionBlocker(p)).contains("learner 'fraud_threshold_tuner' is paused").contains("address_risk");
        assertThatThrownBy(() -> promotion.promote(p.proposalId(), HUMAN, REASON))
            .isInstanceOf(ConflictException.class).hasMessageContaining("learner 'fraud_threshold_tuner' is paused");
        assertThat(liveAct("return_risk")).isEqualTo(0.80);

        // The drafter's own state applies as well: the stricter wins.
        governance.resume("fraud_threshold_tuner", "user:admin");
        governance.setEnabled("band_threshold", false, "user:admin");
        assertThat(promotion.promotionBlocker(p)).contains("learner 'band_threshold' is kill-switched off");
        governance.setEnabled("band_threshold", true, "user:admin");

        // Both clear: the change goes live.
        assertThat(promotion.promotionBlocker(p)).isNull();
        promotion.promote(p.proposalId(), HUMAN);
        assertThat(proposals.get(p.proposalId()).status()).isEqualTo(ProposalStatus.PROMOTED);
        assertThat(liveAct("return_risk")).isEqualTo(0.83);
    }

    @Test
    void theLowerAutonomyOfOwnerAndDrafterGovernsTheAutomaticPath() {
        ownTenant("d4a");
        String name = installProbe("d4a_t0");
        Proposal p = proposal("system:override_to_rule", new ArtifactRef("decision", name), liveVersion(name), PolicyChangeset.RiskTier.T0, ProposalStatus.EVALUATED,
            new PolicyChange.ThresholdChange(name, "act", 0.80, 0.78));
        proposals.markEvaluated(p.proposalId(), autoEvidence(), Instant.now());
        assertThat(ownership.governors(p)).containsExactly("band_threshold", "override_to_rule");
        governance.setAutonomy("band_threshold", AutonomyLevel.L3_AUTONOMOUS, "user:admin");
        governance.setAutonomy("override_to_rule", AutonomyLevel.L0_OBSERVE, "user:admin");
        assertThat(promotion.sweepOnce()).isZero();
        governance.setAutonomy("override_to_rule", AutonomyLevel.L1_RECOMMEND, "user:admin");
        assertThat(promotion.sweepOnce()).isEqualTo(1);
    }
}
