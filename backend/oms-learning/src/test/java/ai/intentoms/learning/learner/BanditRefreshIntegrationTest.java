package ai.intentoms.learning.learner;

import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.learning.governance.LearnerGovernance;
import ai.intentoms.learning.model.AutonomyLevel;
import ai.intentoms.learning.model.Episode;
import ai.intentoms.learning.model.EpisodeStatus;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.learning.support.DecisionFabricTestBase;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NF-02: the bandit's arm weights refresh as soon as one of its episodes matures, without waiting for the sweep (and
 * at most once a minute per tenant). 25b: every bandit record carries the bandit's real latency, not a hard-coded 0.
 */
class BanditRefreshIntegrationTest extends DecisionFabricTestBase {
    @Autowired SourcingPolicyBandit bandit;
    @Autowired BanditRewardLearner learner;
    @Autowired LearnerGovernance governance;
    @Autowired LearnedFactRepository facts;

    @BeforeEach
    void learnersOn() {
        governance.setTenantKillSwitch(false, "user:tester");
        for (String key : List.of(BanditRewardLearner.KEY, SourcingPolicyBandit.KEY)) {
            governance.setEnabled(key, true, "user:tester");
            governance.resume(key, "user:tester");
            governance.setAutonomy(key, AutonomyLevel.L1_RECOMMEND, "user:tester");
        }
    }

    private Event matured(String episodeId) {
        return new Event(Ids.id(), TENANT, "learning", episodeId, 1, "episode.matured", Map.of("decision", SourcingPolicyBandit.DECISION_NAME, "reward", 0.5),
            Actor.system("reward-maturity"), null, null, Map.of(), Instant.now());
    }

    @Test
    void aMaturedBanditEpisodeRefreshesTheWeightsAtOnceAndRecordsCarryRealLatency() throws InterruptedException {
        String channel = "nf02-" + Ids.shortId();
        Map<String, Object> context = Map.of("channel", channel, "intentPolicy", "none", "basketUnits", 1, "destinationZip", "60606");
        String bucket = SourcingPolicyBandit.bucket(context);
        List<String> arms = List.of("ship_complete", "split_up_to_k");

        // 25b: the bandit's time from draw to log is on the record.
        var draw = bandit.draw(arms, "ship_complete", context);
        Thread.sleep(25);
        DecisionRecord timed = records.find(bandit.log("ord_" + Ids.shortId(), draw, draw.applied(), null, "system:test").decisionRecordId()).orElseThrow();
        assertThat(timed.latencyMs()).isGreaterThanOrEqualTo(25);
        assertThat(((Number) timed.details().get("latencyMs")).longValue()).isEqualTo(timed.latencyMs());

        // 24 rewarded choices in one bucket (ship_complete earns more), none of them yet reflected in the weights.
        String lastEpisode = null;
        for (int i = 0; i < 24; i++) {
            String order = "ord_" + Ids.shortId();
            var choice = bandit.choose(order, arms, i % 3 == 0 ? "split_up_to_k" : "ship_complete", context, "system:test");
            Episode e = Episode.open(Ids.prefixed("epi"), choice.decisionRecordId(), SourcingPolicyBandit.DECISION_NAME, order, null, null, 1, "order", order, Instant.now());
            episodes.upsertDecisionPoint(e);
            String id = episodes.findByDecisionId(choice.decisionRecordId()).orElseThrow().episodeId();
            episodes.updateStatusAndReward(id, EpisodeStatus.MATURE, "ship_complete".equals(choice.policy()) ? 0.8 : 0.3, Map.of(), Instant.now());
            lastEpisode = id;
        }
        assertThat(facts.active(SubjectType.SEGMENT, SourcingPolicyBandit.bucketSubject(bucket), FactKey.SOURCING_POLICY_PROPENSITY)).isEmpty();

        learner.on(matured(lastEpisode));
        var weights = facts.active(SubjectType.SEGMENT, SourcingPolicyBandit.bucketSubject(bucket), FactKey.SOURCING_POLICY_PROPENSITY).orElseThrow();
        assertThat(weights.sampleSize()).isEqualTo(24);

        // Another episode of a different decision never triggers it; a second bandit episode within the minute is throttled.
        learner.on(new Event(Ids.id(), TENANT, "learning", "epi_x", 1, "episode.matured", Map.of("decision", "sourcing_node_score"), Actor.system("t"), null, null, Map.of(), Instant.now()));
        learner.on(matured(lastEpisode));
        assertThat(facts.active(SubjectType.SEGMENT, SourcingPolicyBandit.bucketSubject(bucket), FactKey.SOURCING_POLICY_PROPENSITY).orElseThrow().version())
            .isEqualTo(weights.version());
    }
}
