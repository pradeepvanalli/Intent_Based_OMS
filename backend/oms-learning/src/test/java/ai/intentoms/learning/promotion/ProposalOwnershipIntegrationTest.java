package ai.intentoms.learning.promotion;

import ai.intentoms.learning.api.LearningController;
import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.learning.governance.LiveParameterRepository;
import ai.intentoms.learning.governance.ParameterResolver;
import ai.intentoms.learning.learner.WarnEarlyThresholdLearner;
import ai.intentoms.learning.model.ArtifactRef;
import ai.intentoms.learning.model.AutonomyLevel;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.PolicyChangeset;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.ProposalStatus;
import ai.intentoms.learning.support.DecisionFabricTestBase;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.security.Principal;
import ai.intentoms.platform.security.PrincipalAuthentication;
import ai.intentoms.platform.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * N1 (EX-05 for live use) and N5 (a proposal is governed by the learner that owns its target), reproduced the way the
 * audit did: a person promotes live use of a family and its outcome decision degrades; a seeded
 * {@code system:demo-seed.<learner>} T0 proposal and a person's fraud-threshold draft meet their owners' real state.
 * Each test runs in a tenant of its own so other tests' records and learner states cannot leak into the comparison.
 */
class ProposalOwnershipIntegrationTest extends DecisionFabricTestBase {
    @Autowired PromotionService promotion;
    @Autowired LearningController learning;
    @Autowired ParameterResolver parameters;
    @Autowired LiveParameterRepository liveParameters;
    @Autowired LearnerGovernance governance;
    @Autowired ProposalOwnership ownership;

    private static final Actor HUMAN = Actor.parse("user:approver");
    private static final String OVERRIDE = "test: exercising the guardrail monitor, not the evidence gate";

    private String ownTenant(String prefix) {
        String tenant = prefix + "_" + Ids.shortId().toLowerCase();
        jdbc.sql("insert into tenant(tenant_id, name) values (:t, :t) on conflict do nothing").param("t", tenant).update();
        TenantContext.set(tenant);
        SecurityContextHolder.getContext().setAuthentication(new PrincipalAuthentication(
            new Principal(Principal.Type.USER, "tester", tenant, Set.of("admin"), Set.of("*"), 3)));
        return tenant;
    }

    @Test
    void guardrailJobRollsBackAPersonsLiveUsePromotionAndRevokesTheFamily() {
        ownTenant("n1");
        String family = WarnEarlyThresholdLearner.PARAMETER;
        String decision = WarnEarlyThresholdLearner.DECISION;
        Instant before = Instant.now().minus(2, ChronoUnit.HOURS);
        for (int i = 0; i < 10; i++) insertRecord(decision, 1, before.plusSeconds(i), "1", 0.9, 40, null, null, "system:test");

        // As LiveUseController writes it: drafted by a person, not by the learner.
        Proposal p = proposal("user:alice", new ArtifactRef("parameter", "live:" + family), 0, PolicyChangeset.RiskTier.T1, ProposalStatus.APPROVED,
            new PolicyChange.ParameterChange("live:" + family, Map.of("enabled", false),
                Map.of("enabled", true, "min", 1.5, "max", 3.0, "maxStep", 0.5, "learner", WarnEarlyThresholdLearner.KEY)));
        assertThat(ownership.watchedDecisions(p)).contains(decision);
        promotion.promote(p.proposalId(), HUMAN, OVERRIDE);
        assertThat(parameters.isLive(family)).isTrue();

        Instant after = Instant.now().plusMillis(50);
        for (int i = 0; i < 10; i++) {
            var r = insertRecord(decision, 1, after, "2", 0.9, 40, null, null, "system:test");
            if (i < 4) records.override(r.id(), "1", "warned a customer whose order was on time", "user:ops", after);
        }
        while (!Instant.now().isAfter(after.plusMillis(10))) Thread.onSpinWait();

        Map<String, Object> run = learning.runJob("guardrails");
        assertThat(((Number) run.get("result")).intValue()).isEqualTo(1);
        Proposal rolled = proposals.get(p.proposalId());
        assertThat(rolled.status()).isEqualTo(ProposalStatus.ROLLED_BACK);
        assertThat(rolled.rolledBackReason()).contains("guardrail breach").contains(decision + " override rate");
        assertThat(liveParameters.find(family).orElseThrow().status()).isEqualTo("REVOKED");
        assertThat(parameters.isLive(family)).isFalse();
    }

    @Test
    void aSeededLearnerProposalIsGovernedByThatLearnersRealState() {
        ownTenant("n5");
        String name = installProbe("n5_t0");
        Proposal p = proposal("system:demo-seed.band_threshold", new ArtifactRef("decision", name), liveVersion(name), PolicyChangeset.RiskTier.T0, ProposalStatus.EVALUATED,
            new PolicyChange.ThresholdChange(name, "act", 0.80, 0.78));
        proposals.markEvaluated(p.proposalId(), Map.of("liftEstimate", 0.1, "confidenceInterval", Map.of("lower", 0.01, "upper", 0.2), "shadow", Map.of("passed", true)), Instant.now());
        assertThat(ownership.owner(proposals.get(p.proposalId()))).contains("band_threshold");

        // band_threshold is observe-only in this tenant: its seeded T0 change must not promote itself "at L1".
        governance.setAutonomy("band_threshold", AutonomyLevel.L0_OBSERVE, "user:admin");
        assertThat(promotion.sweepOnce()).isZero();
        assertThat(proposals.get(p.proposalId()).status()).isEqualTo(ProposalStatus.EVALUATED);

        // Paused: a hard stop that names the owning learner.
        governance.setAutonomy("band_threshold", AutonomyLevel.L1_RECOMMEND, "user:admin");
        governance.pause("band_threshold", "operator is investigating", "user:admin");
        assertThat(promotion.promotionBlocker(proposals.get(p.proposalId()))).contains("learner 'band_threshold' is paused");
        assertThat(promotion.sweepOnce()).isZero();

        // Its weekly budget applies, not the tenant's per-target default.
        governance.resume("band_threshold", "user:admin");
        governance.setBudget("band_threshold", null, 0, "user:admin");
        assertThat(promotion.sweepOnce()).isZero();
        assertThat(promotion.promotionConcerns(proposals.get(p.proposalId()))).anySatisfy(c -> assertThat(c).contains("budget 0 per week"));

        governance.setBudget("band_threshold", null, 1, "user:admin");
        assertThat(promotion.sweepOnce()).isEqualTo(1);
        assertThat(proposals.get(p.proposalId()).status()).isEqualTo(ProposalStatus.PROMOTED);
    }

    @Test
    void aPersonsFraudThresholdDraftIsGovernedByTheFraudThresholdTuner() {
        ownTenant("n5p");
        Proposal p = proposal("user:alice", new ArtifactRef("decision", "rto_risk"), 1, PolicyChangeset.RiskTier.T1, ProposalStatus.APPROVED,
            new PolicyChange.ThresholdChange("rto_risk", "act", 0.90, 0.92));
        assertThat(ownership.owner(p)).contains("fraud_threshold_tuner");
        assertThat(promotion.governingLearner(p)).isEqualTo("fraud_threshold_tuner");
        governance.setEnabled("fraud_threshold_tuner", false, "user:admin");
        assertThat(promotion.promotionBlocker(p)).contains("learner 'fraud_threshold_tuner' is kill-switched off");

        // A change no learner owns keeps the tier-only default.
        Proposal unowned = proposal("user:alice", new ArtifactRef("config", "orders.some_setting"), 0, PolicyChangeset.RiskTier.T1, ProposalStatus.APPROVED,
            new PolicyChange.ParameterChange("orders.some_setting", 1, 2));
        assertThat(ownership.owner(unowned)).isEmpty();
        assertThat(promotion.promotionBlocker(unowned)).isNull();
    }
}
