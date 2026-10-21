package ai.intentoms.simulation.learning;

import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.model.Mode;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.decision.runtime.DecisionService;
import ai.intentoms.decision.studio.DecisionStudioService;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * D4-1/EX-01 item 1's clip note: the IPS propensity clip must never truncate a decision's own governed exploration
 * floor. A 4-arm T0 decision exploring at a 10% share logs 0.1/3 ≈ 0.033 for its explored arm, below the evaluator's
 * default 0.05 clip; before this fix a threshold evaluation's {@code loggedPropensities.min} would be silently
 * rounded up to 0.05 (clipped), understating how real the explored evidence is. Also covers EX-01 item 7: a T1
 * decision never explores by design, and the report says so explicitly rather than reading as a gap.
 */
@SpringBootTest(classes = TestApplication.class, properties = "oms.workflow.enabled=false")
@ActiveProfiles("test")
@TestPropertySource(properties = {"spring.flyway.ignore-migration-patterns=*:missing,*:future", "oms.learning.replay.min-cohort=3"})
class ProposalEvaluationPropensityFloorIntegrationTest extends PostgresTestBase {
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
        tenant = "floor-" + Ids.shortId().toLowerCase();
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
                  payment: Payment authorization declined
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

    private void reward(DecisionRecord r, double reward) {
        Episode e = Episode.open(Ids.prefixed("epi"), r.id(), r.decision(), r.subjectId(), null, null, r.version(), r.subjectType(), r.subjectId(), r.createdAt());
        episodes.upsertDecisionPoint(e);
        episodes.updateStatusAndReward(e.episodeId(), EpisodeStatus.PROVISIONAL, reward, Map.of(), null);
    }

    private Proposal draft(ArtifactRef target, int base, PolicyChangeset.RiskTier tier, PolicyChange change) {
        Proposal p = new Proposal(Ids.prefixed("prop"), new PolicyChangeset(target, base, List.of(change), "test", tier), null, tier, ProposalStatus.DRAFT, null, null,
            "system:test", Instant.now(), Instant.now(), null, null, null, null, null, null, null, null);
        proposals.insert(p);
        return p;
    }

    @Test
    void aThresholdEvaluationsLoggedPropensitiesMinIsTheExploredArmsNotTheDefaultClip() {
        String name = install("floor_t0", "T0");
        config.set(ExplorationFloor.KEY_PREFIX + name, 0.5); // capped at 10% by the floor itself
        List<DecisionRecord> logged = new ArrayList<>();
        for (int i = 0; i < 300; i++) logged.add(decide(name, i));
        for (DecisionRecord r : logged) reward(r, 0.5);

        Proposal p = draft(new ArtifactRef("decision", name), 1, PolicyChangeset.RiskTier.T0, new PolicyChange.ThresholdChange(name, "act", 0.5, 0.55));
        Map<String, Object> report = evaluations.evaluate(p.proposalId(), "user:tester").evaluationReport();

        @SuppressWarnings("unchecked") Map<String, Object> lp = (Map<String, Object>) report.get("loggedPropensities");
        double expectedFloor = 0.10 / 3; // this decision's own floor: 10% share over 3 other arms
        // the report rounds to 4 decimal places (ProposalEvaluationService.round), so compare at that precision
        assertThat(((Number) lp.get("min")).doubleValue()).as("the explored arm's true propensity, not clipped up to the default 0.05")
            .isCloseTo(expectedFloor, within(5e-5));
        assertThat(((Number) lp.get("clippedAt")).doubleValue()).as("the clip actually used matches this decision's own floor")
            .isCloseTo(expectedFloor, within(5e-5));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aT1DecisionsThresholdEvaluationNamesTheTierRuleInsteadOfLookingLikeAGap() {
        String name = install("floor_t1", "T1");
        List<DecisionRecord> logged = new ArrayList<>();
        for (int i = 0; i < 25; i++) logged.add(decide(name, i));
        for (DecisionRecord r : logged) reward(r, 0.5);

        Proposal p = draft(new ArtifactRef("decision", name), 1, PolicyChangeset.RiskTier.T1, new PolicyChange.ThresholdChange(name, "act", 0.5, 0.55));
        Map<String, Object> report = evaluations.evaluate(p.proposalId(), "user:tester").evaluationReport();

        Map<String, Object> lp = (Map<String, Object>) report.get("loggedPropensities");
        assertThat(((Number) lp.get("belowOne")).longValue()).isZero();
        assertThat(String.valueOf(lp.get("note"))).as("explains the all-1.0 propensities as deliberate, not a gap")
            .contains("§7").contains("T1");
    }
}
