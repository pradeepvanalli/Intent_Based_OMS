package ai.intentoms.sourcing.learning;

import ai.intentoms.learning.model.Episode;
import ai.intentoms.learning.model.EpisodeStatus;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.store.EpisodeRepository;
import ai.intentoms.learning.store.ProposalRepository;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.sourcing.SourcingTestBase;
import ai.intentoms.sourcing.plan.SoftScorer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** FS-04: chosen-plan score components paired with episode rewards become a WeightChange proposal on sourcing.weights. */
class SourcingWeightLearnerIntegrationTest extends SourcingTestBase {
    @Autowired SourcingWeightLearner learner;
    @Autowired EpisodeRepository episodes;
    @Autowired ProposalRepository proposals;

    @Test
    void outcomesThatFollowOnTimePlansShiftWeightTowardTime() {
        for (int i = 0; i < 60; i++) {
            String order = "ord-" + Ids.shortId();
            double time = (i % 10) / 10.0;                       // on-time component varies 0..0.9
            double cost = 0.5 + ((i * 7) % 5) / 10.0;            // cost varies independently of the reward
            Map<String, Object> plan = Map.of("explain", Map.of("components", Map.of("cost", cost, "time", time, "soft", 0.5, "split", 1.0)));
            jdbc.sql("insert into sourcing_plan(id, tenant_id, order_id, policy, plan, score, chosen, created_at) values (:id, :t, :o, 'ship_complete', :p, 0.5, true, now())")
                .param("id", Ids.prefixed("plan")).param("t", tenant).param("o", order).param("p", Jsonb.of(plan)).update();
            String dr = Ids.prefixed("dr");
            episodes.upsertDecisionPoint(Episode.open(Ids.prefixed("epi"), dr, SoftScorer.DECISION, order, null, null, 1, "order", order, Instant.now()));
            episodes.updateStatusAndReward(episodes.findByDecisionId(dr).orElseThrow().episodeId(), EpisodeStatus.PROVISIONAL, 0.2 + time, Map.of("otd", time), null);
        }

        assertThat(learner.sweepOnce()).isEqualTo(1);
        Proposal p = proposals.byStatus("DRAFT", 50).stream().filter(x -> SourcingWeightLearner.TARGET.equals(x.changeset().target().toString())).findFirst().orElseThrow();
        assertThat(p.createdBy()).isEqualTo("system:" + SourcingWeightLearner.KEY);
        assertThat(p.hypothesisId()).isNotNull();
        PolicyChange.WeightChange time = p.changeset().changes().stream().map(c -> (PolicyChange.WeightChange) c).filter(c -> c.weightKey().equals("time")).findFirst().orElseThrow();
        assertThat(time.to()).isGreaterThan(time.from());
        assertThat(time.to() - time.from()).isLessThanOrEqualTo(0.05 + 1e-9);
        // A second run does not stack another proposal on the same target while this one is open.
        assertThat(learner.sweepOnce()).isZero();
    }

    @Test
    void proposedWeightsStayNormalisedAndStepCapped() {
        Map<String, Double> current = Map.of("cost", 0.4, "time", 0.25, "soft", 0.25, "split", 0.1);
        Map<String, Double> next = SourcingWeightLearner.propose(current, Map.of("cost", -1.0, "time", 1.0, "soft", 0.0, "split", 0.0), 0.05);
        assertThat(next.values().stream().mapToDouble(Double::doubleValue).sum()).isCloseTo(1.0, within(0.001));
        assertThat(next.get("time")).isGreaterThan(0.25);
        assertThat(next.get("cost")).isLessThan(0.4);
        List<double[]> rows = List.of(new double[]{0, 0, 0, 0, 0}, new double[]{1, 1, 0, 0, 1});
        assertThat(SourcingWeightLearner.correlations(rows).get("time")).isCloseTo(1.0, within(1e-9));
        assertThat(SourcingWeightLearner.correlations(rows).get("soft")).isZero();
    }
}
