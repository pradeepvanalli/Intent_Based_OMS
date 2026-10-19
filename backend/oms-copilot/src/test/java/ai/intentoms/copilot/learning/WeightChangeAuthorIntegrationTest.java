package ai.intentoms.copilot.learning;

import ai.intentoms.copilot.NoLlmTestBase;
import ai.intentoms.learning.model.Episode;
import ai.intentoms.learning.model.EpisodeStatus;
import ai.intentoms.learning.model.Hypothesis;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.WeightLever;
import ai.intentoms.learning.store.EpisodeRepository;
import ai.intentoms.learning.store.HypothesisRepository;
import ai.intentoms.learning.store.ProposalRepository;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.sourcing.learning.SourcingWeightLearner;
import ai.intentoms.sourcing.plan.SoftScorer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FS-04 end to end without a model key: the sourcing weight learner raises a typed weight hypothesis, the Author's
 * nightly run turns it into a {@code WeightChange} proposal on {@code config:sourcing.weights} (no longer refused as a
 * config target), evaluated with the learner's {@code plan_rescoring_replay}, attributed to the template author.
 */
class WeightChangeAuthorIntegrationTest extends NoLlmTestBase {
    @Autowired SourcingWeightLearner learner;
    @Autowired AuthorScheduler scheduler;
    @Autowired HypothesisRepository hypotheses;
    @Autowired ProposalRepository proposals;
    @Autowired EpisodeRepository episodes;

    String tenant;

    @BeforeEach
    void ownTenant() {
        tenant = "fs04-" + Ids.shortId();
        jdbc.sql("insert into tenant(tenant_id, name) values (:t, :t) on conflict do nothing").param("t", tenant).update();
        TenantContext.set(tenant);
    }

    @AfterEach
    void back() { TenantContext.set(TENANT); }

    /** 60 sourced orders whose reward follows the on-time component; each also had a dearer, more on-time plan it did not pick. */
    private void rewardedHistory() {
        for (int i = 0; i < 60; i++) {
            String order = "ord-" + Ids.shortId();
            double time = (i % 10) / 10.0;
            double cost = 0.5 + ((i * 7) % 5) / 10.0;
            Map<String, Object> plan = Map.of("explain", Map.of("components", Map.of("cost", cost, "time", time, "soft", 0.5, "split", 1.0)));
            jdbc.sql("insert into sourcing_plan(id, tenant_id, order_id, policy, plan, score, chosen, created_at) values (:id, :t, :o, 'ship_complete', :p, 0.5, true, now())")
                .param("id", Ids.prefixed("plan")).param("t", tenant).param("o", order).param("p", Jsonb.of(plan)).update();
            Map<String, Object> alt = Map.of("explain", Map.of("components", Map.of("cost", cost - 0.2, "time", Math.min(1.0, time + 0.3), "soft", 0.5, "split", 1.0)));
            jdbc.sql("insert into sourcing_plan(id, tenant_id, order_id, policy, plan, score, chosen, created_at) values (:id, :t, :o, 'ship_complete', :p, 0.4, false, now())")
                .param("id", Ids.prefixed("plan")).param("t", tenant).param("o", order).param("p", Jsonb.of(alt)).update();
            String dr = Ids.prefixed("dr");
            episodes.upsertDecisionPoint(Episode.open(Ids.prefixed("epi"), dr, SoftScorer.DECISION, order, null, null, 1, "order", order, Instant.now()));
            episodes.updateStatusAndReward(episodes.findByDecisionId(dr).orElseThrow().episodeId(), EpisodeStatus.PROVISIONAL, 0.2 + time, Map.of("otd", time), null);
        }
    }

    @Test
    void theAuthorTurnsTheWeightHypothesisIntoAnEvaluatedWeightChangeProposal() {
        rewardedHistory();
        assertThat(learner.sweepOnce()).isEqualTo(1);
        Hypothesis h = hypotheses.byStatus("OPEN", 50).stream().filter(x -> WeightLever.of(x).isPresent()).findFirst().orElseThrow();
        assertThat(AuthorScheduler.authorable(h)).isTrue();

        Proposal p = scheduler.runOnce().stream().filter(x -> h.hypothesisId().equals(x.hypothesisId())).findFirst().orElseThrow();
        assertThat(p.createdBy()).isEqualTo(TemplateAuthor.ACTOR);
        assertThat(p.changeset().target().toString()).isEqualTo(SourcingWeightLearner.TARGET);
        assertThat(p.changeset().motivatedBy()).startsWith("Template author (no LLM)");
        PolicyChange.WeightChange time = p.changeset().changes().stream().map(c -> (PolicyChange.WeightChange) c).filter(c -> c.weightKey().equals("time")).findFirst().orElseThrow();
        assertThat(time.to()).isGreaterThan(time.from());
        assertThat(p.status().name()).isEqualTo("EVALUATED");
        assertThat(p.evaluationReport()).containsEntry("method", "plan_rescoring_replay");
        assertThat(((Number) p.evaluationReport().get("liftEstimate")).doubleValue()).isGreaterThan(0);
        assertThat(hypotheses.find(h.hypothesisId()).orElseThrow().status()).isEqualTo(Hypothesis.Status.DRAFTED);

        // One proposal per target: the next learner sweep and author run add nothing while it is open.
        assertThat(learner.sweepOnce()).isZero();
        assertThat(scheduler.runOnce()).noneMatch(x -> SourcingWeightLearner.TARGET.equals(x.changeset().target().toString()));
    }

    @Test
    void aWeightHypothesisThatNoLongerStartsFromTheLiveWeightsIsRejected() {
        Map<String, Double> stale = Map.of("cost", 0.7, "time", 0.1, "soft", 0.1, "split", 0.1);
        WeightLever lever = new WeightLever("sourcing.weights", stale, Map.of("cost", 0.65, "time", 0.15, "soft", 0.1, "split", 0.1), Map.of(), 60, 60);
        Hypothesis h = new Hypothesis(Ids.prefixed("hyp"), Map.of("target", lever.target()), "reward", ai.intentoms.learning.model.Direction.INCREASE, lever.toLever(), 0.5,
            java.util.List.of(), "stale weights", Hypothesis.Status.OPEN, Instant.now());
        hypotheses.insert(h);
        assertThat(scheduler.runOnce()).noneMatch(x -> h.hypothesisId().equals(x.hypothesisId()));
        assertThat(hypotheses.find(h.hypothesisId()).orElseThrow().status()).isEqualTo(Hypothesis.Status.REJECTED);
    }
}
