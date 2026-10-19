package ai.intentoms.simulation.learning;

import ai.intentoms.decision.model.Band;
import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.model.Layer;
import ai.intentoms.decision.model.Mode;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.decision.runtime.DecisionService;
import ai.intentoms.decision.studio.DecisionStudioService;
import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.learning.hooks.ExplorationFloor;
import ai.intentoms.learning.model.ArtifactRef;
import ai.intentoms.learning.model.Episode;
import ai.intentoms.learning.model.EpisodeStatus;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.PolicyChangeset;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.ProposalStatus;
import ai.intentoms.learning.store.EpisodeRepository;
import ai.intentoms.learning.store.ProposalRepository;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.testing.PostgresTestBase;
import ai.intentoms.testing.TestApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EX-01: off-policy correction actually operates.
 * <ul>
 *   <li>(b) The governed exploration floor: a T0 decision with {@code learning.exploration.<decision>} applies another
 *       allowed arm on that share of live decisions and logs the true propensity of the applied arm (share / other arms;
 *       1 - share for its normal choice). It is capped at 10%, ignored for non-T0 decisions and for decisions about
 *       refunds, cancellations, pricing or fraud, and off under the learning kill switch. A threshold change on such a
 *       decision is then evaluated with IPS over propensities below 1. Before, every such record logged 1.0.</li>
 *   <li>(a) A change to the sourcing bandit's policy (its exploration share, switching it off) is evaluated with IPS and
 *       doubly robust over the bandit's logged propensities; it used to be reported as having no counterfactual.</li>
 * </ul>
 */
@SpringBootTest(classes = TestApplication.class, properties = "oms.workflow.enabled=false")
@ActiveProfiles("test")
@TestPropertySource(properties = {"spring.flyway.ignore-migration-patterns=*:missing,*:future", "oms.learning.replay.min-cohort=3"})
class OffPolicyWithRealPropensitiesIntegrationTest extends PostgresTestBase {
    @Autowired ProposalEvaluationService evaluations;
    @Autowired DecisionStudioService studio;
    @Autowired DecisionService decisions;
    @Autowired DecisionRecordRepository records;
    @Autowired EpisodeRepository episodes;
    @Autowired ProposalRepository proposals;
    @Autowired TenantConfigStore config;

    private String tenant;

    @BeforeEach
    void ownTenant() {
        tenant = "ex01-" + Ids.shortId().toLowerCase();
        jdbc.sql("insert into tenant(tenant_id, name) values (:t, :t) on conflict do nothing").param("t", tenant).update();
        TenantContext.set(tenant);
    }

    @AfterEach
    void back() { TenantContext.set(TENANT); }

    private static String yaml(String name, String tier) {
        return """
            decision: %s
            version: 1
            owner: test
            tier: %s
            provider: heuristic
            default: unclear
            state:
              include:
                - exception.code
                - exception.message
            questions:
              kind:
                type: choice
                instructions: What kind of exception is this?
                criteria:
                  carrier_delay: The carrier is late with the parcel
                  inventory_short: The node is short of stock
                  unclear: Not enough information to tell
                escapeOption: unclear
            bands:
              act: { confidence_gte: 0.5 }
              escalate: { otherwise: true }
            """.formatted(name, tier);
    }

    private String install(String prefix, String tier) {
        String name = prefix + "_" + Ids.shortId().toLowerCase();
        DecisionDefinition d = studio.createDraft(name, yaml(name, tier));
        studio.publish(name, d.version(), "live", true);
        return name;
    }

    private DecisionRecord decide(String name, int i) {
        String id = "ord_" + i + Ids.shortId();
        DecisionResult r = decisions.decide(name, Map.of("exception", Map.of("code", "late_scan", "message", "the carrier is late with the parcel")),
            new DecisionOptions("order", id, id, Mode.LIVE, null, Actor.system("test"), Map.of()));
        return records.find(r.recordId()).orElseThrow();
    }

    @Test
    void aT0DecisionExploresOnItsGovernedShareAndLogsTruePropensities() {
        String name = install("ex01_t0", "T0");
        config.set(ExplorationFloor.KEY_PREFIX + name, 0.5); // asks for 50%: capped at 10%
        List<DecisionRecord> logged = new ArrayList<>();
        for (int i = 0; i < 400; i++) logged.add(decide(name, i));

        List<DecisionRecord> explored = logged.stream().filter(r -> r.reasonCodes().contains("explored")).toList();
        List<DecisionRecord> normal = logged.stream().filter(r -> !r.reasonCodes().contains("explored")).toList();
        assertThat(explored.size()).as("about 10%% explored: %s", explored.size()).isBetween(16, 70);
        String usual = String.valueOf(normal.get(0).value());
        assertThat(normal).allSatisfy(r -> {
            assertThat(r.propensity()).isEqualTo(0.9);
            assertThat(r.chosenArm()).isEqualTo(usual);
        });
        assertThat(explored).allSatisfy(r -> {
            assertThat(r.propensity()).as("10% over the two other arms").isEqualTo(0.05);
            assertThat(r.chosenArm()).isEqualTo(String.valueOf(r.value())).isNotEqualTo(usual);
            assertThat(r.details()).containsEntry("modelAnswer", usual);
            assertThat((Map<String, Object>) r.details().get("exploration")).containsEntry("explored", true).containsEntry("normalArm", usual).containsEntry("share", 0.1);
        });

        // A threshold change on it is evaluated with IPS over those propensities.
        Random rnd = new Random(7);
        for (DecisionRecord r : logged) reward(r, r.reasonCodes().contains("explored") ? 0.4 + rnd.nextDouble() * 0.2 : 0.8 + rnd.nextDouble() * 0.2);
        Proposal p = draft(new ArtifactRef("decision", name), 1, PolicyChangeset.RiskTier.T0, new PolicyChange.ThresholdChange(name, "act", 0.5, 0.55));
        Map<String, Object> report = evaluations.evaluate(p.proposalId(), "user:tester").evaluationReport();
        assertThat((Map<String, Object>) report.get("loggedPropensities")).containsEntry("records", 400).containsEntry("belowOne", 400).containsEntry("min", 0.05);
        Map<String, Object> ips = (Map<String, Object>) report.get("ips");
        // Normal choices at or above 0.55 keep their arm and are weighted 1/0.9; below it the proposal falls back to the
        // escape arm, which only the explored records ever applied (weighted 1/0.05).
        double ipsEstimate = ((Number) ips.get("proposedRewardEstimate")).doubleValue();
        assertThat(ipsEstimate).as("an IPS estimate built from real propensities").isGreaterThan(0.0);
        assertThat(((Number) report.get("sampleSize")).intValue()).isEqualTo(400);
    }

    @Test
    void explorationIsOnlyForT0DecisionsThatMoveNoMoneyAndStopsUnderTheKillSwitch() {
        String t1 = install("ex01_t1", "T1");
        config.set(ExplorationFloor.KEY_PREFIX + t1, 0.1);
        String refund = install("refund_triage", "T0");
        config.set(ExplorationFloor.KEY_PREFIX + refund, 0.1);
        String t0 = install("ex01_ks", "T0");
        config.set(ExplorationFloor.KEY_PREFIX + t0, 0.1);
        config.set(LearnerGovernance.TENANT_KILL_SWITCH, true);
        try {
            for (int i = 0; i < 60; i++) {
                assertThat(decide(t1, i).propensity()).isEqualTo(1.0);
                assertThat(decide(refund, i).propensity()).isEqualTo(1.0);
                assertThat(decide(t0, i).propensity()).as("learning switched off").isEqualTo(1.0);
            }
        } finally {
            config.delete(LearnerGovernance.TENANT_KILL_SWITCH);
        }
        assertThat(ExplorationFloor.allowed("fraud_review_triage", ai.intentoms.decision.model.Tier.T0)).isFalse();
        assertThat(ExplorationFloor.allowed("pricing_band", ai.intentoms.decision.model.Tier.T0)).isFalse();
        assertThat(ExplorationFloor.allowed("exception_classify", ai.intentoms.decision.model.Tier.T0)).isTrue();
    }

    @Test
    void sourcingBanditPolicyChangesAreEvaluatedOverItsLoggedPropensities() {
        Random rnd = new Random(11);
        List<String> arms = List.of("ship_complete", "split_up_to_k", "ship_as_available");
        Map<String, Object> weights = Map.of("ship_complete", 0.5, "split_up_to_k", 0.2, "ship_as_available", 0.3);
        for (int i = 0; i < 120; i++) {
            // epsilon-greedy at 10% over three arms: the top arm 0.9333, the others 0.0333 each
            boolean explore = rnd.nextDouble() < 0.10;
            String applied = explore ? arms.get(rnd.nextInt(3)) : "ship_complete";
            double p = applied.equals("ship_complete") ? 0.9 + 0.1 / 3 : 0.1 / 3;
            DecisionRecord r = bandit(applied, p, arms, weights);
            reward(r, applied.equals("ship_complete") ? 1.0 + rnd.nextDouble() * 0.1 : 0.7 + rnd.nextDouble() * 0.1);
        }
        Proposal lessExploration = draft(new ArtifactRef("config", "learning.bandit.exploration"), 0, PolicyChangeset.RiskTier.T1,
            new PolicyChange.ParameterChange("learning.bandit.exploration", 0.10, 0.02));
        Map<String, Object> report = evaluations.evaluate(lessExploration.proposalId(), "user:tester").evaluationReport();
        assertThat(report.get("method")).isEqualTo("ips_doubly_robust_logged_propensities");
        assertThat(((Number) report.get("sampleSize")).intValue()).isEqualTo(120);
        assertThat((Map<String, Object>) report.get("loggedPropensities")).containsEntry("belowOne", 120);
        assertThat(((Number) report.get("liftEstimate")).doubleValue()).as("exploring less of a worse arm improves the reward").isPositive();
        assertThat(report).containsKeys("ips", "confidenceInterval", "rewards", "targetPolicy");

        Proposal off = draft(new ArtifactRef("parameter", "live:sourcing.policy_bandit"), 0, PolicyChangeset.RiskTier.T1,
            new PolicyChange.ParameterChange("live:sourcing.policy_bandit", Map.of("enabled", true), Map.of("enabled", false)));
        Map<String, Object> offReport = evaluations.evaluate(off.proposalId(), "user:tester").evaluationReport();
        assertThat(offReport.get("method")).isEqualTo("ips_doubly_robust_logged_propensities");
        assertThat(String.valueOf(offReport.get("targetPolicy"))).contains("off");
    }

    /** A record the way {@code SourcingPolicyBandit.log} writes an acting draw. */
    private DecisionRecord bandit(String applied, double propensity, List<String> arms, Map<String, Object> weights) {
        String id = Ids.prefixed("dr");
        String order = "ord_" + Ids.shortId();
        Map<String, Object> ctx = Map.of("channel", "web", "bucket", "web|none|1|z1|none|calm");
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("acted", true);
        details.put("bucket", "web|none|1|z1|none|calm");
        details.put("appliedPolicy", applied);
        details.put("defaultPolicy", "ship_as_available");
        details.put("exploration", 0.10);
        details.put("weights", weights);
        details.put("randomSeed", 42L);
        details.put("mode", "LIVE");
        DecisionRecord r = new DecisionRecord(id, "sourcing_policy_bandit", 1, Mode.LIVE, "order", order, order, Layer.L1_SYSTEM_ONE, Band.ACT, "act", applied, Map.of(), propensity,
            "bandit", "fs-01", "h_" + id, ctx, ctx, List.of(), List.of(), 1, 0, 0, false, null, "system:sourcing", details, null, null, null, null, Map.of(), Instant.now(), ctx,
            arms, applied, propensity, 1);
        records.insert(r);
        return r;
    }

    private void reward(DecisionRecord r, double reward) {
        Episode e = Episode.open(Ids.prefixed("epi"), r.id(), r.decision(), r.subjectId(), null, null, r.version(), r.subjectType(), r.subjectId(), r.createdAt());
        episodes.upsertDecisionPoint(e);
        episodes.updateStatusAndReward(e.episodeId(), EpisodeStatus.PROVISIONAL, reward, Map.of("otd", 1.0), null);
    }

    private Proposal draft(ArtifactRef target, int base, PolicyChangeset.RiskTier tier, PolicyChange change) {
        Proposal p = new Proposal(Ids.prefixed("prop"), new PolicyChangeset(target, base, List.of(change), "test", tier), null, tier, ProposalStatus.DRAFT, null, null,
            "system:test", Instant.now(), Instant.now(), null, null, null, null, null, null, null, null);
        proposals.insert(p);
        return p;
    }
}
