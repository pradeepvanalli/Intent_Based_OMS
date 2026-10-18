package ai.intentoms.learning.experiment;

import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.model.Mode;
import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.learning.governance.ParameterResolver;
import ai.intentoms.learning.hooks.CurveConfidenceCalibration;
import ai.intentoms.learning.hooks.PromotedProviderRouter;
import ai.intentoms.learning.learner.ProviderRoutingLearner;
import ai.intentoms.learning.model.ArtifactRef;
import ai.intentoms.learning.model.Experiment;
import ai.intentoms.learning.model.ExperimentMode;
import ai.intentoms.learning.model.ExperimentStatus;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.PolicyChangeset;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.ProposalStatus;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.promotion.PromotionService;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.learning.support.DecisionFabricTestBase;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** EX-02 shadow, EX-03 sticky canary + sequential test, EX-04 holdout on the baseline, EX-06, XP-01 lift, DF-02 and DF-05 on the live path. */
class ExperimentLifecycleIntegrationTest extends DecisionFabricTestBase {
    @Autowired ExperimentManager experiments;
    @Autowired ExperimentTrafficRouter router;
    @Autowired LiftService lift;
    @Autowired PromotionService promotion;
    @Autowired LearnedFactRepository facts;
    @Autowired ParameterResolver parameters;
    @Autowired TenantConfigStore config;
    @Autowired LearnerGovernance governance;
    @Autowired ai.intentoms.learning.store.LearnedFactCache factCache;

    private static final String TESTER = "user:tester";

    @Test
    void shadowPublishesARealShadowVersionAndWritesTheVerdictToTheProposal() throws Exception {
        String name = installProbe("f4_shadow");
        int v1 = liveVersion(name);
        Proposal p = proposal("system:band_threshold", new ArtifactRef("decision", name), v1, PolicyChangeset.RiskTier.T1, ProposalStatus.EVALUATED,
            new PolicyChange.ThresholdChange(name, "act", 0.8, 0.5));

        Experiment e = experiments.start(p.proposalId(), ExperimentMode.SHADOW, 0, Map.of("minRecords", 3), TESTER);
        assertThat(e.candidateVersion()).isNotNull();
        assertThat(definitions.find(name, e.candidateVersion()).orElseThrow().definition().status()).isEqualTo("shadow");
        assertThat(e.createdBy()).isEqualTo(TESTER);

        for (int i = 0; i < 6; i++) decideLive(name, "ord_s" + i + Ids.shortId(), 100, null);
        awaitShadowRecords(name, e.candidateVersion(), 6);

        Experiment done = experiments.complete(e.experimentId(), Actor.parse(TESTER));
        assertThat(done.status()).isEqualTo(ExperimentStatus.COMPLETED);
        assertThat(((Number) done.result().get("paired")).longValue()).isGreaterThanOrEqualTo(3);
        assertThat(done.result()).containsKey("passed").containsKey("agreementRate");
        @SuppressWarnings("unchecked") Map<String, Object> shadow = (Map<String, Object>) proposals.get(p.proposalId()).evaluationReport().get("shadow");
        assertThat(shadow).containsEntry("passed", done.result().get("passed"));
        assertThat(proposals.get(p.proposalId()).evaluationReport()).containsKey("liftEstimate"); // replay section kept
        assertThat(definitions.find(name, e.candidateVersion()).orElseThrow().definition().status()).isEqualTo("retired");
    }

    @Test
    void canaryRoutesAStickyShareToTheCandidateAndTheSequentialTestDecides() {
        String name = installProbe("f4_canary");
        int v1 = liveVersion(name);
        Proposal p = proposal("system:band_threshold", new ArtifactRef("decision", name), v1, PolicyChangeset.RiskTier.T1, ProposalStatus.EVALUATED,
            new PolicyChange.ThresholdChange(name, "act", 0.8, 0.5));
        Experiment e = experiments.start(p.proposalId(), ExperimentMode.CANARY, 0.5, Map.of("minPerArm", 5), TESTER);
        assertThat(proposals.get(p.proposalId()).status()).isEqualTo(ProposalStatus.CANARY);
        assertThat(definitions.find(name, e.candidateVersion()).orElseThrow().definition().status()).isEqualTo("draft"); // never published

        Proposal sibling = proposal("system:band_threshold", new ArtifactRef("decision", name), v1, PolicyChangeset.RiskTier.T1, ProposalStatus.EVALUATED,
            new PolicyChange.ThresholdChange(name, "act", 0.8, 0.6));
        assertThatThrownBy(() -> experiments.start(sibling.proposalId(), ExperimentMode.CANARY, 0.2, Map.of(), TESTER))
            .isInstanceOf(ConflictException.class).hasMessageContaining("EX-06");

        List<String> canaryKeys = new ArrayList<>(), controlKeys = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            String key = "cust_" + i;
            var route = router.route(name, key, v1);
            if (route.isPresent() && "canary".equals(route.get().arm())) canaryKeys.add(key);
            else if (route.isEmpty()) controlKeys.add(key);
            assertThat(router.route(name, key, v1)).isEqualTo(route); // sticky
        }
        assertThat(canaryKeys.size()).isBetween(20, 60);

        for (int i = 0; i < 10; i++) {
            DecisionResult t = decideLive(name, "ord_t" + i + Ids.shortId(), 100, canaryKeys.get(i));
            assertThat(t.version()).isEqualTo(e.candidateVersion());
            DecisionRecord tr = records.find(t.recordId()).orElseThrow();
            assertThat(tr.details()).containsKey("experiment");
            rewardEpisode(tr, 1.0 + i * 0.01, Map.of("otd", 1.0));
            DecisionResult c = decideLive(name, "ord_c" + i + Ids.shortId(), 100, controlKeys.get(i));
            assertThat(c.version()).isEqualTo(v1);
            rewardEpisode(records.find(c.recordId()).orElseThrow(), 0.5 + i * 0.01, Map.of("otd", 0.5));
        }

        Experiment done = experiments.complete(e.experimentId(), Actor.parse(TESTER));
        @SuppressWarnings("unchecked") Map<String, Object> msprt = (Map<String, Object>) done.result().get("msprt");
        assertThat(msprt.get("verdict")).isEqualTo("TREATMENT_BETTER");
        Proposal after = proposals.get(p.proposalId());
        assertThat(after.status()).isEqualTo(ProposalStatus.EVALUATED);
        assertThat(after.evaluationReport()).containsKey("canary");
        assertThat(router.route(name, canaryKeys.get(0), v1)).isEmpty();
    }

    @Test
    void holdoutKeepsItsShareOnTheBaselineVersionAndLiftComparesAgainstIt() {
        String name = installProbe("f4_holdout");
        int v1 = liveVersion(name);
        Proposal p = proposal("system:band_threshold", new ArtifactRef("decision", name), v1, PolicyChangeset.RiskTier.T1, ProposalStatus.APPROVED,
            new PolicyChange.ThresholdChange(name, "act", 0.8, 0.7));
        promotion.promote(p.proposalId(), Actor.parse("user:approver"));
        int v2 = liveVersion(name);
        assertThat(v2).isGreaterThan(v1);

        Experiment holdout = experiments.ensureHoldout(0.3, TESTER);
        try {
            assertThat(experiments.ensureHoldout(0.1, TESTER).experimentId()).isEqualTo(holdout.experimentId());
            String heldKey = null, treatedKey = null;
            for (int i = 0; i < 200 && (heldKey == null || treatedKey == null); i++) {
                var route = router.route(name, "k" + i, v2);
                if (route.isPresent() && "holdout".equals(route.get().arm())) heldKey = "k" + i; else if (route.isEmpty()) treatedKey = "k" + i;
            }
            assertThat(router.route(name, heldKey, v2).orElseThrow().version()).isEqualTo(v1);
            DecisionResult held = decideLive(name, "ord_h" + Ids.shortId(), 100, heldKey);
            assertThat(held.version()).isEqualTo(v1);
            assertThat(decideLive(name, "ord_n" + Ids.shortId(), 100, treatedKey).version()).isEqualTo(v2);

            Instant now = Instant.now();
            for (int i = 0; i < 8; i++) {
                rewardEpisode(insertRecord(name, v1, now, "approve", 0.9, 30, Map.of("experiment", Map.of("experimentId", holdout.experimentId(), "arm", "holdout")), null, "system:test"),
                    0.4 + i * 0.01, Map.of("otd", 0.5, "cx", 0.2));
                rewardEpisode(insertRecord(name, v2, now, "approve", 0.9, 30, null, null, "system:test"), 0.9 + i * 0.01, Map.of("otd", 1.0, "cx", 0.0));
            }
            Map<String, Object> report = lift.lift(30);
            @SuppressWarnings("unchecked") List<Map<String, Object>> metrics = (List<Map<String, Object>>) report.get("metrics");
            Map<String, Object> reward = metrics.stream().filter(m -> "reward".equals(m.get("metric"))).findFirst().orElseThrow();
            assertThat(((Number) reward.get("nHoldout")).intValue()).isGreaterThanOrEqualTo(8);
            assertThat(((Number) reward.get("lift")).doubleValue()).isGreaterThan(0);
            Map<String, Object> cx = metrics.stream().filter(m -> "cx".equals(m.get("metric"))).findFirst().orElseThrow();
            assertThat(cx.get("betterWhen")).isEqualTo("lower");
        } finally {
            experiments.stop(holdout.experimentId(), ExperimentStatus.STOPPED, Map.of(), Actor.parse(TESTER));
        }
    }

    @Test
    void calibrationCurveRemapsConfidenceOnlyWhileItsFamilyIsLive() {
        String name = installProbe("f4_calib");
        DecisionResult before = decideLive(name, "ord_b" + Ids.shortId(), 100, null);
        assertThat(before.details()).doesNotContainKey("rawConfidence");

        Instant now = Instant.now();
        facts.insert(new LearnedFact(Ids.prefixed("fact"), SubjectType.DECISION, name, FactKey.CALIBRATION_CURVE,
            new TypedValue.ListValue(List.of("0.00-0.50:+0.000", "0.50-0.70:+0.300", "0.70-1.00:+0.000")), 1.0, 40, "confidence_calibrator", 1, now, null, now));
        factCache.invalidateAll(); // the first decision cached "no curve" for a few seconds
        parameters.promote(CurveConfidenceCalibration.FAMILY, "confidence_calibrator", 0.0, 1.0, null, null, TESTER);
        try {
            DecisionResult after = decideLive(name, "ord_a" + Ids.shortId(), 100, null);
            double raw = ((Number) after.details().get("rawConfidence")).doubleValue();
            assertThat(after.confidence()).isCloseTo(raw + 0.3, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(after.details()).containsKey("calibration");
        } finally {
            parameters.revoke(CurveConfidenceCalibration.FAMILY, TESTER);
        }
    }

    @Test
    void promotedProviderRouteIsTriedFirstAndStopsWhenTheLearnerIsSwitchedOff() {
        String name = "f4_route_" + Ids.shortId().toLowerCase();
        DecisionDefinition d = studio.createDraft(name, probeYaml(name, null).replace("provider: heuristic", "provider: jev\nfallbackProvider: heuristic"));
        studio.publish(name, d.version(), "live", true);
        assertThat(decideLive(name, "ord_r0" + Ids.shortId(), 100, null).details()).doesNotContainKey("providerRouted");

        config.set(PromotedProviderRouter.KEY_PREFIX + name, "\"heuristic\"");
        DecisionResult routed = decideLive(name, "ord_r1" + Ids.shortId(), 100, null);
        assertThat(routed.details()).containsEntry("providerRouted", "heuristic");
        assertThat(routed.provider()).isEqualTo("heuristic");

        governance.setEnabled(ProviderRoutingLearner.KEY, false, TESTER);
        try {
            assertThat(decideLive(name, "ord_r2" + Ids.shortId(), 100, null).details()).doesNotContainKey("providerRouted");
        } finally {
            governance.setEnabled(ProviderRoutingLearner.KEY, true, TESTER);
            config.delete(PromotedProviderRouter.KEY_PREFIX + name);
        }
    }

    private void awaitShadowRecords(String name, int version, int n) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        while (Instant.now().isBefore(deadline)) {
            long c = jdbc.sql("select count(*) from decision_record where tenant_id = :t and decision = :d and version = :v and mode = :m")
                .param("t", TENANT).param("d", name).param("v", version).param("m", Mode.SHADOW.name()).query(Long.class).single();
            if (c >= n) return;
            Thread.sleep(100);
        }
        throw new AssertionError("shadow records did not appear");
    }
}
