package ai.intentoms.learning.explain;

import ai.intentoms.learning.LearningFixtures;
import ai.intentoms.learning.api.ExplainabilityController;
import ai.intentoms.learning.model.Direction;
import ai.intentoms.learning.model.Episode;
import ai.intentoms.learning.model.EpisodeStatus;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.Hypothesis;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.OutcomeData;
import ai.intentoms.learning.model.OutcomeRecord;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.EpisodeRepository;
import ai.intentoms.learning.store.HypothesisRepository;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.learning.store.OutcomeRepository;
import ai.intentoms.learning.store.ProposalRepository;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.testing.PostgresTestBase;
import ai.intentoms.testing.TestApplication;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** XP-02 change card and cumulative diff, XP-04 weekly digest, NF-09 learner metrics, NF-10 retention, against PostgreSQL. */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class ExplainabilityIntegrationTest extends PostgresTestBase {
    @Autowired ProposalRepository proposals;
    @Autowired HypothesisRepository hypotheses;
    @Autowired EpisodeRepository episodes;
    @Autowired OutcomeRepository outcomes;
    @Autowired LearnedFactRepository facts;
    @Autowired ExplainabilityController api;
    @Autowired ChangeCardService cards;
    @Autowired WeeklyLearningDigestService digests;
    @Autowired LearnerMetricsService learnerMetrics;
    @Autowired LearningRetentionService retention;
    @Autowired MeterRegistry registry;

    private Proposal promoted(String decision, double from, double to, String approvedBy, Instant at) {
        Proposal p = LearningFixtures.threshold(decision, from, to, "system:band_threshold", null);
        proposals.insert(p);
        if (approvedBy != null) proposals.approve(p.proposalId(), approvedBy, at.minusSeconds(60));
        proposals.promote(p.proposalId(), at);
        return p;
    }

    @Test
    void changeCardShowsTheCumulativeDriftSinceTheLastHumanApprovedBaseline() {
        String decision = "card_" + Ids.shortId();
        Instant t0 = Instant.now().minus(Duration.ofDays(10));
        Proposal base = promoted(decision, 0.80, 0.82, "user:lead", t0);
        promoted(decision, 0.82, 0.84, null, t0.plus(Duration.ofDays(2)));
        promoted(decision, 0.84, 0.86, "system:promotion-service", t0.plus(Duration.ofDays(4)));
        Hypothesis h = new Hypothesis(Ids.prefixed("hyp"), Map.of("decision", decision), "override_rate", Direction.DECREASE, Map.of(), 0.7,
            List.of("cohort node=DC-NJ: 41 episodes"), "Accuracy holds above 0.86", Hypothesis.Status.DRAFTED, Instant.now());
        hypotheses.insert(h);
        Proposal pending = LearningFixtures.threshold(decision, 0.86, 0.88, "system:band_threshold", h.hypothesisId());
        proposals.insert(pending);
        proposals.markEvaluated(pending.proposalId(), Map.of("method", "doubly_robust", "liftEstimate", 0.02, "confidenceInterval", Map.of("lower", 0.004, "upper", 0.036), "sampleSize", 412), Instant.now());

        ChangeCardService.ChangeCard card = api.card(pending.proposalId());

        assertThat(card.hypothesis().rationale()).isEqualTo("Accuracy holds above 0.86");
        assertThat(card.evidence()).containsExactly("cohort node=DC-NJ: 41 episodes");
        assertThat(card.replay()).containsEntry("evaluated", true).containsEntry("liftEstimate", 0.02).containsKey("confidenceInterval");
        assertThat(card.diff()).singleElement().satisfies(d -> { assertThat(d.from()).isEqualTo(0.86); assertThat(d.to()).isEqualTo(0.88); });
        CumulativeDiffService.CumulativeDiff c = card.cumulative();
        assertThat(c.baseline().proposalId()).isEqualTo(base.proposalId());
        assertThat(c.automaticPromotionsSinceBaseline()).isEqualTo(2);
        assertThat(c.sinceBaseline()).singleElement().satisfies(d -> { assertThat(d.from()).isEqualTo(0.82); assertThat(d.to()).isEqualTo(0.86); assertThat(d.steps()).isEqualTo(2); });
        assertThat(c.withPending()).singleElement().satisfies(d -> { assertThat(d.from()).isEqualTo(0.82); assertThat(d.to()).isEqualTo(0.88); assertThat(d.steps()).isEqualTo(3); });
        assertThat(card.actions().canApprove()).isTrue();
        assertThat(cards.card(pending.proposalId(), "system:band_threshold").actions().canApprove()).isFalse();
        assertThat(api.diff("decision:" + decision).promotedProposalIds()).hasSize(3);
    }

    @Test
    void weeklyDigestSummarisesChangesImpactAndPending() {
        String decision = "digest_" + Ids.shortId();
        promoted(decision, 0.80, 0.82, "user:lead", Instant.now().minus(Duration.ofDays(1)));
        proposals.insert(LearningFixtures.threshold(decision, 0.82, 0.84, "system:band_threshold", null));
        for (int i = 0; i < 3; i++) {
            Episode e = Episode.open(Ids.prefixed("epi"), "dr-" + Ids.shortId(), decision, "o-" + Ids.shortId(), null, null, 1, "order", "o-" + i + decision, Instant.now().minus(Duration.ofDays(2)));
            episodes.upsertDecisionPoint(e);
            episodes.updateStatusAndReward(e.episodeId(), EpisodeStatus.MATURE, 0.5 + i * 0.1, Map.of("otd", 1.0), Instant.now().minus(Duration.ofHours(5)));
        }

        WeeklyLearningDigestService.Digest d = digests.publish(7, "user:tester");

        assertThat(d.delivery()).isEqualTo("stored_only");
        assertThat(d.summary().get("headline").toString()).contains("promoted");
        @SuppressWarnings("unchecked") Map<String, Object> impact = (Map<String, Object>) d.summary().get("impact");
        @SuppressWarnings("unchecked") List<Map<String, Object>> rewards = (List<Map<String, Object>>) impact.get("rewardByDecision");
        assertThat(rewards).anySatisfy(r -> { assertThat(r.get("decision")).isEqualTo(decision); assertThat(((Number) r.get("maturedEpisodes")).intValue()).isEqualTo(3); });
        @SuppressWarnings("unchecked") Map<String, Object> changed = (Map<String, Object>) d.summary().get("changed");
        assertThat((List<?>) changed.get("promoted")).isNotEmpty();
        assertThat(api.latestDigest().digestId()).isEqualTo(d.digestId());
        assertThat(WeeklyLearningDigestService.render(d.summary())).contains("What changed").contains("What is pending");
        assertThat(jdbc.sql("select count(*) from event where tenant_id = :t and type = 'learning.digest_published' and aggregate_id = :id")
            .param("t", TENANT).param("id", d.digestId()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void learnerMetricsReportYieldPromotionAndRollbackRates() {
        Proposal a = LearningFixtures.threshold("metrics_" + Ids.shortId(), 0.8, 0.82, "system:override_to_rule", null);
        Proposal b = LearningFixtures.threshold("metrics_" + Ids.shortId(), 0.8, 0.82, "system:override_to_rule", null);
        proposals.insert(a);
        proposals.insert(b);
        proposals.promote(a.proposalId(), Instant.now());
        proposals.rollback(a.proposalId(), "guardrail breach", Instant.now());

        LearnerMetricsService.LearnerMetrics m = api.metrics().stream().filter(x -> x.learnerKey().equals("override_to_rule")).findFirst().orElseThrow();
        assertThat(m.proposalsDrafted()).isGreaterThanOrEqualTo(2);
        assertThat(m.proposalsPromoted()).isGreaterThanOrEqualTo(1);
        assertThat(m.rollbackRate()).isGreaterThan(0);

        learnerMetrics.refreshGauges();
        assertThat(registry.find("oms.learning.learner.promotion_rate").tag("tenant", TENANT).tag("learner", "override_to_rule").gauge()).isNotNull();
        assertThat(registry.find("oms.learning.learner.update_lag_seconds").tag("learner", "node_reliability").gauges()).isNotEmpty();
    }

    @Test
    void retentionRemovesOnlyWhatIsOlderThanTheWindowAndDryRunRemovesNothing() {
        String decision = "ret_" + Ids.shortId();
        Instant old = Instant.now().minus(Duration.ofDays(401));
        Episode oldEp = Episode.open(Ids.prefixed("epi"), "dr-" + Ids.shortId(), decision, "o-old", null, null, 1, "order", "o-old-" + decision, old);
        Episode youngEp = Episode.open(Ids.prefixed("epi"), "dr-" + Ids.shortId(), decision, "o-young", null, null, 1, "order", "o-young-" + decision, Instant.now().minus(Duration.ofDays(30)));
        episodes.upsertDecisionPoint(oldEp);
        episodes.upsertDecisionPoint(youngEp);
        episodes.updateStatusAndReward(oldEp.episodeId(), EpisodeStatus.MATURE, 0.4, Map.of(), old.plus(Duration.ofDays(120)));
        episodes.updateStatusAndReward(youngEp.episodeId(), EpisodeStatus.MATURE, 0.6, Map.of(), Instant.now());
        outcomes.insert(OutcomeRecord.of(Ids.prefixed("out"), oldEp.episodeId(), new OutcomeData.DeliveryOutcome(true, 0, false, false), old, Ids.id(), old));
        String node = "RET-" + Ids.shortId();
        facts.insert(new LearnedFact(Ids.prefixed("fact"), SubjectType.NODE, node, FactKey.NODE_ON_TIME_RATE, new TypedValue.NumberValue(0.9), 0.5, 10, "test", 1, old, old.plusSeconds(60), old));
        facts.insert(new LearnedFact(Ids.prefixed("fact"), SubjectType.NODE, node, FactKey.NODE_ON_TIME_RATE, new TypedValue.NumberValue(0.92), 0.5, 12, "test", 2, old.plusSeconds(60), null, old));

        Map<String, Object> dry = api.retention(true);
        assertThat(((Number) dry.get("episodes")).longValue()).isGreaterThanOrEqualTo(1);
        assertThat(episodes.find(oldEp.episodeId())).isPresent();
        assertThat(dry).containsEntry("retentionDays", 400);

        Map<String, Object> applied = retention.run(false, "user:tester");
        assertThat(((Number) applied.get("outcomes")).longValue()).isGreaterThanOrEqualTo(1);
        assertThat(episodes.find(oldEp.episodeId())).isEmpty();
        assertThat(episodes.find(youngEp.episodeId())).isPresent();
        assertThat(facts.active(SubjectType.NODE, node, FactKey.NODE_ON_TIME_RATE)).isPresent();
        assertThat(jdbc.sql("select count(*) from learned_fact where tenant_id = :t and subject_id = :n").param("t", TENANT).param("n", node).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from event where tenant_id = :t and type = 'learning.retention_run'").param("t", TENANT).query(Long.class).single()).isGreaterThanOrEqualTo(2);
        assertThat(jdbc.sql("select count(*) from episode where tenant_id = :t and created_at < :c").param("t", TENANT).param("c", Timestamp.from(Instant.now().minus(Duration.ofDays(400)))).query(Long.class).single()).isZero();
    }
}
