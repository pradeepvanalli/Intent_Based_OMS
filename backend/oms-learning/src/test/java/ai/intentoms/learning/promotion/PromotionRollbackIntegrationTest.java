package ai.intentoms.learning.promotion;

import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.model.Layer;
import ai.intentoms.learning.governance.ParameterResolver;
import ai.intentoms.learning.model.ArtifactRef;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.PolicyChangeset;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.ProposalStatus;
import ai.intentoms.learning.store.LearningBaselineRepository;
import ai.intentoms.learning.support.DecisionFabricTestBase;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.ledger.Actor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** EX-07 (every change type applied), AC4 (tested rollback for decision, config and parameter targets) and EX-05. */
class PromotionRollbackIntegrationTest extends DecisionFabricTestBase {
    @Autowired PromotionService promotion;
    @Autowired GuardrailBreachMonitor guardrails;
    @Autowired TenantConfigStore config;
    @Autowired ParameterResolver parameters;
    @Autowired LearningBaselineRepository baselines;

    private static final Actor HUMAN = Actor.parse("user:approver");
    /** These tests exercise how each change type is applied and rolled back, not the gate (see PromotionGuardTest). */
    private static final String OVERRIDE = "test: exercising application and rollback, not the evidence gate";

    @Test
    void thresholdPromotionPublishesANewVersionAndRollbackRestoresThePreviousOne() {
        String name = installProbe("f4_thr");
        int v1 = liveVersion(name);
        Proposal p = proposal("system:band_threshold", new ArtifactRef("decision", name), v1, PolicyChangeset.RiskTier.T1, ProposalStatus.APPROVED,
            new PolicyChange.ThresholdChange(name, "act", 0.8, 0.75));

        promotion.promote(p.proposalId(), HUMAN, OVERRIDE);

        int v2 = liveVersion(name);
        assertThat(v2).isGreaterThan(v1);
        assertThat(live(name).effectiveBands().actGte()).isEqualTo(0.75);
        assertThat(proposals.get(p.proposalId()).status()).isEqualTo(ProposalStatus.PROMOTED);
        assertThat(baselines.baseline(name)).contains(v1);

        promotion.rollback(p.proposalId(), HUMAN, "test rollback");

        assertThat(liveVersion(name)).isEqualTo(v1);
        assertThat(live(name).effectiveBands().actGte()).isEqualTo(0.8);
        Proposal after = proposals.get(p.proposalId());
        assertThat(after.status()).isEqualTo(ProposalStatus.ROLLED_BACK);
        assertThat(after.rolledBackReason()).isEqualTo("test rollback");
    }

    @Test
    void rollbackRestoresTheReplacedVersionEvenWhenAnotherVersionWasRetiredSince() {
        String name = installProbe("f4_thr2");
        int v1 = liveVersion(name);
        Proposal p = proposal("system:band_threshold", new ArtifactRef("decision", name), v1, PolicyChangeset.RiskTier.T1, ProposalStatus.APPROVED,
            new PolicyChange.ThresholdChange(name, "act", 0.8, 0.72));
        promotion.promote(p.proposalId(), HUMAN, OVERRIDE);
        // A finished shadow (or any other version) retired after the promotion.
        var other = studio.createDraft(name, probeYaml(name, null).replace("confidence_gte: 0.8", "confidence_gte: 0.66"));
        definitions.setStatus(name, other.version(), "retired", null);

        promotion.rollback(p.proposalId(), HUMAN, null);

        assertThat(live(name).effectiveBands().actGte()).isEqualTo(0.8);
        assertThat(live(name).rules()).extracting(r -> r.id()).containsExactly("big_order", "never_fires");
    }

    @Test
    void ruleAddAndRuleRetireAreAppliedToTheLiveDecision() {
        String name = installProbe("f4_rules");
        Proposal p = proposal("system:author", new ArtifactRef("decision", name), liveVersion(name), PolicyChangeset.RiskTier.T1, ProposalStatus.APPROVED,
            new PolicyChange.RuleAdd(name, "input.order.channel == 'marketplace'", "review", "marketplace_review"),
            new PolicyChange.RuleRetire(name, "never_fires", "never fired"));

        promotion.promote(p.proposalId(), HUMAN, OVERRIDE);

        var rules = live(name).rules();
        assertThat(rules).extracting(r -> r.id()).contains("big_order").doesNotContain("never_fires");
        assertThat(rules).anyMatch(r -> r.when().contains("marketplace") && "review".equals(r.then()));
        DecisionResult r = decisions.decide(name, Map.of("order", Map.of("id", "o1", "channel", "marketplace", "value", 10)),
            ai.intentoms.decision.model.DecisionOptions.live("order", "o1", Actor.system("test")));
        assertThat(r.layer()).isEqualTo(Layer.L0_RULE);
        assertThat(r.value()).isEqualTo("review");
    }

    @Test
    void decisionWeightAndProviderChangesApply() {
        String name = installProbe("f4_fields");
        Proposal p = proposal("system:provider_routing", new ArtifactRef("decision", name), liveVersion(name), PolicyChangeset.RiskTier.T1, ProposalStatus.APPROVED,
            new PolicyChange.ParameterChange("fallbackProvider", null, "heuristic"), new PolicyChange.ParameterChange("timeoutMs", 800, 900));
        promotion.promote(p.proposalId(), HUMAN, OVERRIDE);
        assertThat(live(name).fallbackProvider()).isEqualTo("heuristic");
        assertThat(live(name).timeoutMs()).isEqualTo(900);
    }

    @Test
    @SuppressWarnings("unchecked")
    void configWeightChangeKeepsTheVectorSummingToOneAndRollsBack() {
        String key = "sourcing.weights_test_" + Instant.now().toEpochMilli();
        config.set(key, Map.of("cost", 0.5, "speed", 0.3, "reliability", 0.2));
        Proposal p = proposal("system:sourcing_policy_bandit", new ArtifactRef("config", key), 0, PolicyChangeset.RiskTier.T1, ProposalStatus.APPROVED,
            new PolicyChange.WeightChange(key, "cost", 0.5, 0.4));

        promotion.promote(p.proposalId(), HUMAN, OVERRIDE);

        Map<String, Object> w = config.getMap(key);
        assertThat(((Number) w.get("cost")).doubleValue()).isEqualTo(0.4);
        double sum = w.values().stream().mapToDouble(v -> ((Number) v).doubleValue()).sum();
        assertThat(sum).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(((Number) w.get("speed")).doubleValue()).isCloseTo(0.36, org.assertj.core.data.Offset.offset(1e-6));

        promotion.rollback(p.proposalId(), HUMAN, null);
        assertThat(((Number) config.getMap(key).get("cost")).doubleValue()).isEqualTo(0.5);
    }

    @Test
    void allowListChangeAppliesAndRefundIsRefusedEvenWhenApproved() {
        String key = "autonomy.allowed_actions_test_" + Instant.now().toEpochMilli();
        Proposal add = proposal("system:author", new ArtifactRef("config", key), 0, PolicyChangeset.RiskTier.T1, ProposalStatus.APPROVED,
            new PolicyChange.AllowListChange(key, "reroute_carrier", true));
        promotion.promote(add.proposalId(), HUMAN, OVERRIDE);
        assertThat(config.getList(key)).containsExactly("reroute_carrier");

        Proposal refund = proposal("system:author", new ArtifactRef("config", key), 0, PolicyChangeset.RiskTier.T1, ProposalStatus.APPROVED,
            new PolicyChange.AllowListChange(key, "refund", true));
        assertThatThrownBy(() -> promotion.promote(refund.proposalId(), HUMAN, OVERRIDE)).isInstanceOf(ConflictException.class).hasMessageContaining("never autonomous");
        assertThat(config.getList(key)).containsExactly("reroute_carrier");

        promotion.rollback(add.proposalId(), HUMAN, null);
        assertThat(config.get(key)).isEmpty();
    }

    @Test
    void liveUseOfALearnedParameterIsGrantedAndRollbackRevokesIt() {
        // A family a live path reads (an unknown one is refused at promotion, as at drafting); the shared tenant may have it live.
        String family = ai.intentoms.learning.hooks.NodeReliabilityFeatures.REJECT_RATE;
        parameters.revoke(family, "test-setup");
        Proposal p = proposal("system:node_reliability", new ArtifactRef("parameter", "live:" + family), 0, PolicyChangeset.RiskTier.T0, ProposalStatus.APPROVED,
            new PolicyChange.ParameterChange(family, Map.of("enabled", false), Map.of("enabled", true, "min", 0.0, "max", 0.5, "maxStep", 0.1)));

        promotion.promote(p.proposalId(), HUMAN, OVERRIDE);
        assertThat(parameters.isLive(family)).isTrue();
        assertThat(parameters.resolve(family, 0.2, 0.9)).isCloseTo(0.3, org.assertj.core.data.Offset.offset(1e-9)); // max 0.5, then max step 0.1 from 0.2

        promotion.rollback(p.proposalId(), HUMAN, null);
        assertThat(parameters.isLive(family)).isFalse();
        assertThat(parameters.resolve(family, 0.2, 0.9)).isEqualTo(0.2);
    }

    @Test
    void rollbackRefusesWhenALaterChangeIsLive() {
        String name = installProbe("f4_stack");
        Proposal first = proposal("system:band_threshold", new ArtifactRef("decision", name), liveVersion(name), PolicyChangeset.RiskTier.T1, ProposalStatus.APPROVED,
            new PolicyChange.ThresholdChange(name, "act", 0.8, 0.78));
        promotion.promote(first.proposalId(), HUMAN, OVERRIDE);
        Proposal second = proposal("system:band_threshold", new ArtifactRef("decision", name), liveVersion(name), PolicyChangeset.RiskTier.T1, ProposalStatus.APPROVED,
            new PolicyChange.ThresholdChange(name, "act", 0.78, 0.76));
        promotion.promote(second.proposalId(), HUMAN, OVERRIDE);

        assertThatThrownBy(() -> promotion.rollback(first.proposalId(), HUMAN, null)).isInstanceOf(ConflictException.class).hasMessageContaining("roll back the later change first");
        promotion.rollback(second.proposalId(), HUMAN, null);
        assertThat(live(name).effectiveBands().actGte()).isEqualTo(0.78);
    }

    @Test
    void guardrailMonitorRollsBackAPromotionWhoseOverrideRateJumped() {
        String name = installProbe("f4_guard");
        int v1 = liveVersion(name);
        Instant before = Instant.now().minus(2, ChronoUnit.HOURS);
        for (int i = 0; i < 10; i++) insertRecord(name, v1, before.plusSeconds(i), "approve", 0.9, 40, null, null, "system:test");
        Proposal p = proposal("system:band_threshold", new ArtifactRef("decision", name), v1, PolicyChangeset.RiskTier.T1, ProposalStatus.APPROVED,
            new PolicyChange.ThresholdChange(name, "act", 0.8, 0.76));
        promotion.promote(p.proposalId(), HUMAN, OVERRIDE);
        int v2 = liveVersion(name);
        Instant after = Instant.now().plusMillis(50);
        for (int i = 0; i < 10; i++) {
            var r = insertRecord(name, v2, after, "approve", 0.9, 40, null, null, "system:test");
            if (i < 4) records.override(r.id(), "review", "wrong", "user:ops", after);
        }
        // Seeded reference data never counts toward a guardrail.
        for (int i = 0; i < 20; i++) insertRecord(name, v2, after, "approve", 0.9, 40, null, null, "system:demo-seed");

        sleepUntilAfter(after);
        assertThat(guardrails.sweepOnce()).isGreaterThanOrEqualTo(1);
        Proposal rolled = proposals.get(p.proposalId());
        assertThat(rolled.status()).isEqualTo(ProposalStatus.ROLLED_BACK);
        assertThat(rolled.rolledBackReason()).contains("override rate");
        assertThat(liveVersion(name)).isEqualTo(v1);
    }

    @Test
    void guardrailComparisonRules() {
        var base = new ai.intentoms.learning.experiment.ExperimentMetricsRepository.Guardrails(100, 0.01, 120, 0, 0, 100, 0.05);
        assertThat(GuardrailBreachMonitor.compare("d", base, new ai.intentoms.learning.experiment.ExperimentMetricsRepository.Guardrails(100, 0.02, 130, 0, 0, 100, 0.06), 30)).isNull();
        assertThat(GuardrailBreachMonitor.compare("d", base, new ai.intentoms.learning.experiment.ExperimentMetricsRepository.Guardrails(100, 0.01, 400, 0, 0, 100, 0.05), 30)).contains("latency");
        assertThat(GuardrailBreachMonitor.compare("d", base, new ai.intentoms.learning.experiment.ExperimentMetricsRepository.Guardrails(100, 0.01, 120, 0, 0, 100, 0.09), 30)).contains("contact");
        var fraudBase = new ai.intentoms.learning.experiment.ExperimentMetricsRepository.Guardrails(100, 0.01, 120, 50, 0.10, 0, 0);
        assertThat(GuardrailBreachMonitor.compare("d", fraudBase, new ai.intentoms.learning.experiment.ExperimentMetricsRepository.Guardrails(100, 0.01, 120, 50, 0.15, 0, 0), 30)).contains("false-positive");
        assertThat(GuardrailBreachMonitor.compare("d", base, new ai.intentoms.learning.experiment.ExperimentMetricsRepository.Guardrails(10, 0.5, 900, 0, 0, 0, 0), 30)).isNull();
    }

    private static void sleepUntilAfter(Instant t) {
        while (!Instant.now().isAfter(t.plusMillis(10))) Thread.onSpinWait();
    }

    @SuppressWarnings("unused")
    private static List<String> ids(List<Proposal> ps) { return ps.stream().map(Proposal::proposalId).toList(); }
}
