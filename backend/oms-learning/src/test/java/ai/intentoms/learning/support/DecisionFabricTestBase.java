package ai.intentoms.learning.support;

import ai.intentoms.decision.definition.DecisionDefinitionRepository;
import ai.intentoms.decision.model.Answer;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Integration base for the decision-fabric learning tests: installs a small decision that runs on the deterministic
 * heuristic provider (no keys), with one rule that fires on big orders and one that never fires, and offers helpers
 * for proposals, live decisions, crafted records and episodes.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.flyway.ignore-migration-patterns=*:missing,*:future",
    "oms.learning.guardrail-min-sample=5",
    "oms.learning.df07.min-sample=5",
    "oms.learning.df07.min-labelled=3",
    "oms.learning.replay.min-cohort=3"
})
public abstract class DecisionFabricTestBase extends PostgresTestBase {
    @Autowired protected DecisionStudioService studio;
    @Autowired protected DecisionDefinitionRepository definitions;
    @Autowired protected DecisionService decisions;
    @Autowired protected DecisionRecordRepository records;
    @Autowired protected ProposalRepository proposals;
    @Autowired protected EpisodeRepository episodes;

    protected static String probeYaml(String name, String extra) {
        return """
            decision: %s
            version: 1
            owner: test
            tier: T1
            provider: heuristic
            timeoutMs: 800
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
              - id: never_fires
                when: "input.order.value < 0"
                then: review
                reason: impossible
                priority: 20
            bands:
              act: { confidence_gte: 0.8 }
              confirm: { confidence_gte: 0.5 }
              escalate: { otherwise: true }
            %s
            """.formatted(name, extra == null ? "" : extra);
    }

    /** Installs a probe decision as live version 1 and returns its name. */
    protected String installProbe(String prefix) { return installProbe(prefix, null); }

    protected String installProbe(String prefix, String extraYaml) {
        String name = prefix + "_" + Ids.shortId().toLowerCase();
        DecisionDefinition d = studio.createDraft(name, probeYaml(name, extraYaml));
        studio.publish(name, d.version(), "live", true);
        return name;
    }

    protected int liveVersion(String name) {
        return definitions.byStatus(name, "live").get(0).definition().version();
    }

    protected DecisionDefinition live(String name) { return definitions.byStatus(name, "live").get(0).definition(); }

    protected static Map<String, Object> order(String id, double value) {
        return Map.of("order", Map.of("id", id, "channel", "web", "value", value));
    }

    protected DecisionResult decideLive(String name, String subjectId, double value, String customerRef) {
        Map<String, Object> extra = customerRef == null ? Map.of() : Map.of("customerRef", customerRef);
        return decisions.decide(name, order(subjectId, value), new DecisionOptions("order", subjectId, subjectId, Mode.LIVE, null, Actor.system("test"), extra));
    }

    protected Proposal proposal(String createdBy, ArtifactRef target, int baseVersion, PolicyChangeset.RiskTier tier, ProposalStatus status, PolicyChange... changes) {
        PolicyChangeset cs = new PolicyChangeset(target, baseVersion, List.of(changes), "test proposal", tier);
        Proposal p = new Proposal(Ids.prefixed("prop"), cs, null, tier, ProposalStatus.DRAFT, null, null, createdBy, Instant.now(), Instant.now(),
            null, null, null, null, null, null, null, null);
        proposals.insert(p);
        if (status == ProposalStatus.EVALUATED || status == ProposalStatus.APPROVED) {
            proposals.markEvaluated(p.proposalId(), Map.of("liftEstimate", 0.1, "confidenceInterval", Map.of("lower", 0.01, "upper", 0.2)), Instant.now());
        }
        if (status == ProposalStatus.APPROVED) proposals.approve(p.proposalId(), "user:reviewer", Instant.now());
        return proposals.get(p.proposalId());
    }

    /** A crafted LIVE record (for windows and guardrails that need control over time, version and outcome). */
    protected DecisionRecord insertRecord(String decision, int version, Instant createdAt, String value, double confidence, long latencyMs, Map<String, Object> details,
                                          Map<String, Object> labels, String actor) {
        String id = Ids.prefixed("dr");
        String subject = "ord_" + Ids.shortId();
        DecisionRecord r = new DecisionRecord(id, decision, version, Mode.LIVE, "order", subject, subject, Layer.L1_SYSTEM_ONE, confidence >= 0.8 ? Band.ACT : Band.CONFIRM,
            "act", value, Map.<String, Answer>of(), confidence, "heuristic", "test", "hash_" + id, Map.of(), order(subject, 100), List.of(), List.of(), latencyMs, 0, 0, false,
            null, actor, details == null ? Map.of() : details, null, null, null, null, labels == null ? Map.of() : labels, createdAt, Map.of(), List.of("approve", "review", "unclear"),
            value, 1.0, version);
        records.insert(r);
        return r;
    }

    /** An episode anchored on a record, with a reward (and reward components) already computed. */
    protected void rewardEpisode(DecisionRecord r, double reward, Map<String, Double> components) {
        Episode e = Episode.open(Ids.prefixed("epi"), r.id(), r.decision(), r.subjectId(), null, null, r.version(), r.subjectType(), r.subjectId(), r.createdAt());
        episodes.upsertDecisionPoint(e);
        episodes.updateStatusAndReward(e.episodeId(), EpisodeStatus.PROVISIONAL, reward, components, null);
    }
}
