package ai.intentoms.learning.service;

import ai.intentoms.learning.model.Episode;
import ai.intentoms.learning.model.EpisodeStatus;
import ai.intentoms.learning.store.EpisodeRepository;
import ai.intentoms.learning.store.OutcomeRepository;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.testing.PostgresTestBase;
import ai.intentoms.testing.TestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** OC-02 / OC-03 / OC-04 through the real database: one episode per decision point, idempotent outcomes, provisional reward. */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class EpisodePipelineIntegrationTest extends PostgresTestBase {
    @Autowired EpisodeRepository episodes;
    @Autowired OutcomeRepository outcomes;
    @Autowired OutcomeObserver observer;
    @Autowired RewardMaturityService rewards;

    private Event delivered(String id, String orderId) {
        return new Event(id, TenantContext.require(), "order", orderId, 1, "order.delivered", Map.of("onTime", true, "lateByHours", 0),
            Actor.system("test"), null, orderId, Map.of(), Instant.now());
    }

    @Test
    void reEvaluationsShareOneEpisodeAndOutcomesAttachOnce() {
        String order = "ord-" + Ids.shortId();
        Instant t = Instant.now();
        boolean first = episodes.upsertDecisionPoint(Episode.open(Ids.prefixed("epi"), "dr-1-" + order, "sla_breach_risk", order, null, null, 1, "order", order, t));
        boolean second = episodes.upsertDecisionPoint(Episode.open(Ids.prefixed("epi"), "dr-2-" + order, "sla_breach_risk", order, null, null, 1, "order", order, t));
        boolean redelivered = episodes.upsertDecisionPoint(Episode.open(Ids.prefixed("epi"), "dr-2-" + order, "sla_breach_risk", order, null, null, 1, "order", order, t));
        episodes.upsertDecisionPoint(Episode.open(Ids.prefixed("epi"), "dr-3-" + order, "sourcing_node_score", order, null, null, 1, "order", order, t));

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(redelivered).isFalse();
        List<Episode> forOrder = episodes.forOrder(order);
        assertThat(forOrder).hasSize(2);
        Episode breach = forOrder.stream().filter(e -> e.decisionName().equals("sla_breach_risk")).findFirst().orElseThrow();
        assertThat(breach.evaluationCount()).isEqualTo(2);
        assertThat(breach.lastDecisionId()).isEqualTo("dr-2-" + order);
        assertThat(episodes.findByDecisionId("dr-2-" + order)).map(Episode::episodeId).contains(breach.episodeId());

        Event ev = delivered(Ids.id(), order);
        observer.on(ev);
        observer.on(ev); // redelivery
        for (Episode e : forOrder) assertThat(outcomes.forEpisode(e.episodeId())).hasSize(1);

        rewards.sweepOnce();
        Episode after = episodes.find(breach.episodeId()).orElseThrow();
        assertThat(after.status()).isEqualTo(EpisodeStatus.PROVISIONAL);
        assertThat(after.reward()).isNotNull();
        assertThat(after.rewardComponents()).containsEntry("otd", 1.0);
    }
}
