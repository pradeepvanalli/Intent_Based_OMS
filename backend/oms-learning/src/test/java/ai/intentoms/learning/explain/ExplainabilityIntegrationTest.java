package ai.intentoms.learning.explain;

import ai.intentoms.decision.model.Answer;
import ai.intentoms.decision.model.Band;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.Layer;
import ai.intentoms.decision.model.Mode;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.learning.LearningFixtures;
import ai.intentoms.learning.api.ExplainabilityController;
import ai.intentoms.learning.experiment.ExperimentManager;
import ai.intentoms.learning.experiment.LiftService;
import ai.intentoms.learning.model.Direction;
import ai.intentoms.learning.model.Episode;
import ai.intentoms.learning.model.EpisodeStatus;
import ai.intentoms.learning.model.Experiment;
import ai.intentoms.learning.model.ExperimentStatus;
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
import ai.intentoms.platform.ledger.Actor;
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
    @Autowired DecisionRecordRepository records;
    @Autowired ExperimentManager experiments;
    @Autowired LiftService lift;

    private Proposal promoted(String decision, double from, double to, String approvedBy, Instant at) {
        Proposal p = LearningFixtures.threshold(decision, from, to, "system:band_threshold", null);
        proposals.insert(p);
        proposals.markEvaluated(p.proposalId(), Map.of("liftEstimate", 0.01), at.minusSeconds(120));
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
        assertThat(impact).containsKey("liftVsHoldout"); // XP-04: lift against the holdout (null while none is running)
        @SuppressWarnings("unchecked") Map<String, Object> changed = (Map<String, Object>) d.summary().get("changed");
        assertThat((List<?>) changed.get("promoted")).isNotEmpty();
        assertThat(api.latestDigest().digestId()).isEqualTo(d.digestId());
        assertThat(WeeklyLearningDigestService.render(d.summary())).contains("What changed").contains("What is pending");
        assertThat(jdbc.sql("select count(*) from event where tenant_id = :t and type = 'learning.digest_published' and aggregate_id = :id")
            .param("t", TENANT).param("id", d.digestId()).query(Long.class).single()).isEqualTo(1);
    }

    /** XP-04: LiftService.lift() computes a rich perfect-order headline (the §12 AC-2 north-star figure), but
     * {@code WeeklyLearningDigestService.liftSummary()}'s copy loop used to whitelist only per-metric reward-term
     * fields and never read {@code report.get("headline")}, so it was silently dropped before reaching the digest. */
    @Test
    @SuppressWarnings("unchecked")
    void weeklyDigestLiftSummaryIncludesTheHeadlineLiftServiceComputes() {
        String decision = "digest_headline_" + Ids.shortId();
        Experiment holdout = experiments.ensureHoldout(0.3, "user:tester");
        try {
            Instant now = Instant.now();
            for (int i = 0; i < 3; i++) {
                DecisionRecord held = digestRecord(decision, now, Map.of("experiment", Map.of("experimentId", holdout.experimentId(), "arm", "holdout")));
                rewardDigestEpisode(held, 0.5, Map.of("perfect", 0.0, "perfect_lines", 0.0, "lines", 1.0));
                DecisionRecord treated = digestRecord(decision, now, null);
                rewardDigestEpisode(treated, 0.9, Map.of("perfect", 1.0, "perfect_lines", 1.0, "lines", 1.0));
            }

            WeeklyLearningDigestService.Digest d = digests.publish(7, "user:tester");
            Map<String, Object> impact = (Map<String, Object>) d.summary().get("impact");
            Map<String, Object> liftVsHoldout = (Map<String, Object>) impact.get("liftVsHoldout");
            assertThat(liftVsHoldout).as("a holdout is running, so the lift section must now be present").isNotNull();
            Map<String, Object> headline = (Map<String, Object>) liftVsHoldout.get("headline");
            assertThat(headline).as("the digest's north-star figure, not silently dropped").isNotNull();
            assertThat(headline.get("label")).isEqualTo("Perfect-order rate");
            assertThat(((Number) headline.get("nTreated")).intValue()).isGreaterThanOrEqualTo(3);
            assertThat(((Number) headline.get("nHoldout")).intValue()).isGreaterThanOrEqualTo(3);

            // Matches what /learning/lift itself reports for the same decisions (the live check's "before/after").
            Map<String, Object> direct = (Map<String, Object>) ((Map<String, Object>) lift.lift(90)).get("headline");
            assertThat(headline.get("label")).isEqualTo(direct.get("label"));
            assertThat(headline.get("nTreated")).isEqualTo(direct.get("nTreated"));
            assertThat(headline.get("nHoldout")).isEqualTo(direct.get("nHoldout"));
            assertThat(headline.get("lift")).isEqualTo(direct.get("lift"));
        } finally {
            experiments.stop(holdout.experimentId(), ExperimentStatus.STOPPED, Map.of(), Actor.parse("user:tester"));
        }
    }

    private DecisionRecord digestRecord(String decision, Instant createdAt, Map<String, Object> details) {
        String id = Ids.prefixed("dr");
        String subject = "ord_" + Ids.shortId();
        DecisionRecord r = new DecisionRecord(id, decision, 1, Mode.LIVE, "order", subject, subject, Layer.L1_SYSTEM_ONE, Band.ACT, "act", "approve",
            Map.<String, Answer>of(), 0.9, "heuristic", "test", "hash_" + id, Map.of(), Map.of("order", Map.of("id", subject, "channel", "web", "value", 100)),
            List.of(), List.of(), 30L, 0, 0, false, null, "system:test", details == null ? Map.of() : details, null, null, null, null, Map.of(), createdAt,
            Map.of(), List.of("approve", "review", "unclear"), "approve", 1.0, 1);
        records.insert(r);
        return r;
    }

    private void rewardDigestEpisode(DecisionRecord r, double reward, Map<String, Double> components) {
        Episode e = Episode.open(Ids.prefixed("epi"), r.id(), r.decision(), r.subjectId(), null, null, r.version(), r.subjectType(), r.subjectId(), r.createdAt());
        episodes.upsertDecisionPoint(e);
        episodes.updateStatusAndReward(e.episodeId(), EpisodeStatus.PROVISIONAL, reward, components, null);
    }

    @Test
    void learnerMetricsReportYieldPromotionAndRollbackRates() {
        Proposal a = LearningFixtures.threshold("metrics_" + Ids.shortId(), 0.8, 0.82, "system:override_to_rule", null);
        Proposal b = LearningFixtures.threshold("metrics_" + Ids.shortId(), 0.8, 0.82, "system:override_to_rule", null);
        proposals.insert(a);
        proposals.insert(b);
        proposals.markEvaluated(a.proposalId(), Map.of("liftEstimate", 0.01), Instant.now());
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

    /** E-3: author, template and seed drafts count for the learner that owns their target, not for nobody. */
    @Test
    void yieldCountsDraftsByOthersForTheOwningLearner() {
        long before = api.metrics().stream().filter(x -> x.learnerKey().equals("band_threshold")).findFirst().orElseThrow().proposalsDraftedByOthers();
        proposals.insert(LearningFixtures.threshold("e3_" + Ids.shortId(), 0.8, 0.82, "system:template-author", null));
        proposals.insert(LearningFixtures.threshold("e3_" + Ids.shortId(), 0.8, 0.82, "user:alice", null));
        proposals.insert(LearningFixtures.threshold("e3_" + Ids.shortId(), 0.8, 0.82, "system:demo-seed.band_threshold", null));
        LearnerMetricsService.LearnerMetrics m = api.metrics().stream().filter(x -> x.learnerKey().equals("band_threshold")).findFirst().orElseThrow();
        assertThat(m.proposalsDrafted()).isGreaterThanOrEqualTo(3);
        assertThat(m.proposalsDraftedByOthers()).isEqualTo(before + 2); // the seed drafted as band_threshold itself
    }

    /** E-2: the card states the lift is in reward (not a percentage) and surfaces a significantly negative IPS estimate. */
    @Test
    void theCardSurfacesASignificantlyNegativeIpsEstimate() {
        Proposal p = LearningFixtures.threshold("e2_" + Ids.shortId(), 0.8, 0.82, "system:band_threshold", null);
        proposals.insert(p);
        proposals.markEvaluated(p.proposalId(), Map.of("method", "ips_doubly_robust", "liftEstimate", 0.162, "confidenceInterval", Map.of("lower", 0.02, "upper", 0.3),
            "baselineReward", 0.41, "proposedRewardEstimate", 0.572,
            "ips", Map.of("liftEstimate", -0.12, "confidenceInterval", Map.of("lower", -0.2, "upper", -0.04))), Instant.now());
        ChangeCardService.ChangeCard card = api.card(p.proposalId());
        assertThat(card.replay()).containsEntry("liftUnit", "reward").containsEntry("liftEstimate", 0.162).containsEntry("baselineReward", 0.41);
        assertThat((List<String>) card.replay().get("concerns")).singleElement().asString()
            .isEqualTo("the plain IPS estimate says it makes outcomes worse (reward -0.120, 95% interval -0.200 to -0.040)");
        assertThat(card.governingLearner()).isEqualTo("band_threshold");
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
