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
    @Autowired ai.intentoms.learning.api.EpisodeController episodeApi;

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

        // The read API shows each episode of the order with the outcome that landed on it.
        var views = episodeApi.forOrder(order);
        assertThat(views).hasSize(2);
        assertThat(views).allSatisfy(v -> assertThat(v.outcomes()).extracting(o -> o.payload().getClass().getSimpleName()).containsExactly("DeliveryOutcome"));
    }

    private Event returnEvent(String type, String orderId, String rmaId, Map<String, Object> facts) {
        Map<String, Object> p = new java.util.LinkedHashMap<>(facts);
        p.put("orderId", orderId);
        p.put("rmaId", rmaId);
        return new Event(Ids.id(), TenantContext.require(), "return", rmaId, 1, type, p, Actor.system("test"), null, orderId, Map.of(), Instant.now());
    }

    @Test
    void anOutcomeCommittedAfterTheSweepReadTheEpisodeIsStillScored() {
        String order = "ord-" + Ids.shortId(), rma = "rma-" + Ids.shortId();
        String id = Ids.prefixed("epi");
        episodes.upsertDecisionPoint(Episode.open(id, "dr-" + order, "sourcing_node_score", order, null, null, 1, "order", order, Instant.now()));
        observer.on(new Event(Ids.id(), TenantContext.require(), "order", order, 1, "order.shipped", Map.of("orderId", order, "orderValue", 100.0, "unitsOrdered", 1,
            "unitsShipped", 1, "shipmentId", "shp-" + order), Actor.system("test"), null, order, Map.of(), Instant.now()));
        observer.on(returnEvent("return.dispositioned", order, rma, Map.of("reasonCode", "not_as_described", "fraudConfirmed", true, "refundAmount", 68.0)));
        rewards.sweepOnce();
        assertThat(episodes.find(id).orElseThrow().rewardComponents()).containsEntry("fraud", 0.68);

        // The refund merges into the return's outcome inside the relay's batch transaction, which commits only after a
        // sweep has already read the episode: on commit the outcome's created_at is older than reward_computed_at.
        observer.on(returnEvent("return.refunded", order, rma, Map.of("reasonCode", "not_as_described", "fraudConfirmed", true, "refundAmount", 72.25, "disposition", "destroy")));
        jdbc.sql("update outcome set created_at = created_at - interval '1 minute' where episode_id = :e").param("e", id).update();
        rewards.sweepOnce();
        assertThat(outcomes.forEpisode(id)).filteredOn(o -> o.payload() instanceof ai.intentoms.learning.model.OutcomeData.ReturnOutcome).singleElement()
            .satisfies(o -> assertThat(((ai.intentoms.learning.model.OutcomeData.ReturnOutcome) o.payload()).refundAmount()).isEqualTo(72.25));
        Episode rescored = episodes.find(id).orElseThrow();
        assertThat(rescored.rewardComponents().get("fraud")).as("scored from the merged refund, not the pending estimate").isCloseTo(0.7225, org.assertj.core.api.Assertions.within(1e-9));
        assertThat(jdbc.sql("select outcome_version = reward_outcome_version from episode where episode_id = :e").param("e", id).query(Boolean.class).single()).isTrue();
    }
}
