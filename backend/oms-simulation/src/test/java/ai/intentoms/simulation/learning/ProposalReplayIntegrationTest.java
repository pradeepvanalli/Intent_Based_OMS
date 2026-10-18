package ai.intentoms.simulation.learning;

import ai.intentoms.decision.definition.DecisionDefinitionRepository;
import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.model.Mode;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.decision.runtime.DecisionService;
import ai.intentoms.decision.studio.DecisionStudioService;
import ai.intentoms.learning.hooks.PromotedProviderRouter;
import ai.intentoms.learning.model.ArtifactRef;
import ai.intentoms.learning.model.Episode;
import ai.intentoms.learning.model.EpisodeStatus;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.PolicyChangeset;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.ProposalStatus;
import ai.intentoms.learning.store.EpisodeRepository;
import ai.intentoms.learning.store.ProposalRepository;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.testing.PostgresTestBase;
import ai.intentoms.testing.TestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** EX-01: replay covers every change type on decision targets, and is explicit when a config or parameter target has no counterfactual. */
@SpringBootTest(classes = TestApplication.class, properties = "oms.workflow.enabled=false")
@ActiveProfiles("test")
@TestPropertySource(properties = {"spring.flyway.ignore-migration-patterns=*:missing,*:future", "oms.learning.replay.min-cohort=3"})
class ProposalReplayIntegrationTest extends PostgresTestBase {
    @Autowired ProposalEvaluationService evaluations;
    @Autowired DecisionStudioService studio;
    @Autowired DecisionDefinitionRepository definitions;
    @Autowired DecisionService decisions;
    @Autowired DecisionRecordRepository records;
    @Autowired EpisodeRepository episodes;
    @Autowired ProposalRepository proposals;

    static String yaml(String name) {
        return """
            decision: %s
            version: 1
            owner: test
            tier: T1
            provider: heuristic
            default: review
            state:
              include:
                - order.channel
                - order.value
            questions:
              action:
                type: choice
                instructions: Should this order be approved or reviewed?
                criteria:
                  approve: Approve the order, it looks normal
                  review: Send the order to a person for review
                  unclear: Not enough information to tell
            rules:
              - id: big_order
                when: "input.order.value > 1000"
                then: review
                reason: big_order
                priority: 10
            bands:
              act: { confidence_gte: 0.5 }
              confirm: { confidence_gte: 0.3 }
              escalate: { otherwise: true }
            """.formatted(name);
    }

    private String installWithHistory(int n) {
        String name = "f4_replay_" + Ids.shortId().toLowerCase();
        DecisionDefinition d = studio.createDraft(name, yaml(name));
        studio.publish(name, d.version(), "live", true);
        for (int i = 0; i < n; i++) {
            String id = "ord_" + i + Ids.shortId();
            DecisionResult r = decisions.decide(name, Map.of("order", Map.of("id", id, "channel", "web", "value", 100 + i)),
                new DecisionOptions("order", id, id, Mode.LIVE, null, Actor.system("test"), Map.of()));
            DecisionRecord rec = records.find(r.recordId()).orElseThrow();
            Episode e = Episode.open(Ids.prefixed("epi"), rec.id(), name, id, null, null, rec.version(), "order", id, rec.createdAt());
            episodes.upsertDecisionPoint(e);
            episodes.updateStatusAndReward(e.episodeId(), EpisodeStatus.PROVISIONAL, 0.5 + (i % 5) * 0.1, Map.of("otd", 1.0), null);
        }
        return name;
    }

    private Proposal draft(ArtifactRef target, int base, PolicyChange... changes) {
        Proposal p = new Proposal(Ids.prefixed("prop"), new PolicyChangeset(target, base, List.of(changes), "test", PolicyChangeset.RiskTier.T1), null,
            PolicyChangeset.RiskTier.T1, ProposalStatus.DRAFT, null, null, "system:author", Instant.now(), Instant.now(), null, null, null, null, null, null, null, null);
        proposals.insert(p);
        return p;
    }

    @Test
    void ruleChangesAreResimulatedThroughTheCandidateDefinition() {
        String name = installWithHistory(25);
        int v = definitions.byStatus(name, "live").get(0).definition().version();
        Proposal p = draft(new ArtifactRef("decision", name), v, new PolicyChange.RuleAdd(name, "input.order.value > 50", "approve", "fast_lane"));

        Map<String, Object> report = evaluations.evaluate(p.proposalId(), "user:tester").evaluationReport();

        assertThat(report).containsEntry("method", "resimulation+ips_doubly_robust").containsKeys("liftEstimate", "confidenceInterval");
        assertThat(((Number) report.get("replayed")).intValue()).isEqualTo(25);
        assertThat(((Number) report.get("decisionsChanged")).intValue()).isEqualTo(25); // the new rule decides every one of them
        assertThat(report.get("changeTypes")).isEqualTo(List.of("RuleAdd"));
        assertThat(proposals.get(p.proposalId()).status()).isEqualTo(ProposalStatus.EVALUATED);
    }

    @Test
    void retiringARuleAndChangingWeightsOrProviderAreReplayedToo() {
        String name = installWithHistory(10);
        int v = definitions.byStatus(name, "live").get(0).definition().version();
        Proposal p = draft(new ArtifactRef("decision", name), v, new PolicyChange.RuleRetire(name, "big_order", "unused"),
            new PolicyChange.ParameterChange("fallbackProvider", null, "heuristic"));
        Map<String, Object> report = evaluations.evaluate(p.proposalId(), "user:tester").evaluationReport();
        assertThat(report.get("method")).isEqualTo("resimulation+ips_doubly_robust");
        assertThat(((Number) report.get("decisionsChanged")).intValue()).isZero(); // no logged decision was a big order
    }

    @Test
    void thresholdOnlyChangesUseTheLoggedConfidence() {
        String name = installWithHistory(10);
        int v = definitions.byStatus(name, "live").get(0).definition().version();
        Proposal p = draft(new ArtifactRef("decision", name), v, new PolicyChange.ThresholdChange(name, "act", 0.5, 0.99));
        Map<String, Object> report = evaluations.evaluate(p.proposalId(), "user:tester").evaluationReport();
        assertThat(report.get("method")).isEqualTo("ips_doubly_robust");
        assertThat(((Number) report.get("sampleSize")).intValue()).isEqualTo(10);
    }

    @Test
    void configAndParameterTargetsWithoutACounterfactualSaySoInsteadOfInventingLift() {
        String name = installWithHistory(5);
        Proposal route = draft(new ArtifactRef("config", PromotedProviderRouter.KEY_PREFIX + name), 0,
            new PolicyChange.ParameterChange(PromotedProviderRouter.KEY_PREFIX + name, "heuristic", "jev"));
        Map<String, Object> r1 = evaluations.evaluate(route.proposalId(), "user:tester").evaluationReport();
        assertThat(r1.get("method")).isEqualTo("no_counterfactual");
        assertThat(r1).doesNotContainKey("liftEstimate");

        Proposal live = draft(new ArtifactRef("parameter", "live:sourcing.node_reject_rate"), 0,
            new PolicyChange.ParameterChange("sourcing.node_reject_rate", Map.of("enabled", false), Map.of("enabled", true)));
        Map<String, Object> r2 = evaluations.evaluate(live.proposalId(), "user:tester").evaluationReport();
        assertThat(r2.get("method")).isEqualTo("no_counterfactual");

        Proposal weights = draft(new ArtifactRef("config", "sourcing.weights"), 0, new PolicyChange.WeightChange("sourcing.weights", "cost", 0.5, 0.4));
        assertThat(evaluations.evaluate(weights.proposalId(), "user:tester").evaluationReport().get("method")).isEqualTo("no_counterfactual");
    }
}
