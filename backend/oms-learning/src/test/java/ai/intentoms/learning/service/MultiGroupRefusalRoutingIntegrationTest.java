package ai.intentoms.learning.service;

import ai.intentoms.learning.model.Episode;
import ai.intentoms.learning.model.EpisodeStatus;
import ai.intentoms.learning.model.OutcomeData;
import ai.intentoms.learning.model.OutcomeRecord;
import ai.intentoms.learning.store.EpisodeRepository;
import ai.intentoms.learning.store.OutcomeRepository;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
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

/**
 * D5-1 (OC-02/OC-07, audit 5): {@code order.resourced} can name more than one group in {@code rejectedGroupIds} in a
 * single call — {@code ReshipAction.run()} reaches this via {@code sourcing.resource(orderId, null, "reship",
 * actor)} whenever an order has more than one active fulfilment group, rejecting every active group at once.
 * {@code CoreOutcomeMapper.refusal()} keys its one {@link OutcomeData.FulfillmentOutcome} to only
 * {@code rejectedGroupIds.get(0)}; without the fix, {@link OutcomeObserver#attach} and {@link GroupLineage#owners}
 * only ever route it to the episode that planned that first group, leaving any other rejected group's own planning
 * episode {@code OPEN} forever (A4-2's exact symptom, through a new door).
 *
 * <p>Note on reachability: {@code DefaultSourcingService.resource()} re-plans every one of the order's currently
 * unshipped lines in a single call (confirmed by reading {@code persist()}, which keeps only groups whose status is
 * already inactive and otherwise replaces the <em>entire</em> active set with the new plan's packages) — so two
 * groups that are simultaneously active and unshipped always currently share the same {@code decisionRecordId}, and
 * the multi-group-routing bug this item fixes cannot surface through {@code ReshipAction} on today's code. The two
 * decisions this test uses to pin the real contract (two {@code order.sourced} events, each naming only its own
 * group, exactly as two independent re-sourcing decisions would) are therefore built directly against the real
 * ledger and the real {@link EpisodeRepository}, rather than through a full order lifecycle that cannot reach this
 * state — but every class the fix touches ({@link CoreOutcomeMapper}, {@link OutcomeObserver}, {@link GroupLineage},
 * {@link OutcomeRepository}, {@link RewardMaturityService}) runs for real, through the real Spring context.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class MultiGroupRefusalRoutingIntegrationTest extends PostgresTestBase {
    private static final Actor SYSTEM = Actor.system("test");

    @Autowired EventStore eventStore;
    @Autowired EpisodeRepository episodes;
    @Autowired OutcomeRepository outcomes;
    @Autowired OutcomeObserver observer;
    @Autowired RewardMaturityService rewards;

    @Test
    void everyRejectedGroupsPlanningEpisodeGetsADefiniteNonBlamingOutcome() {
        String order = "ord-" + Ids.shortId();
        String groupA = "fg-" + Ids.shortId(), groupB = "fg-" + Ids.shortId();
        String decisionA = "dr-a-" + order, decisionB = "dr-b-" + order;

        // Two groups of the same order, each planned by a DIFFERENT decision — exactly GroupLineage's own "a
        // re-sourcing decision's episode is keyed by the group it replaced" case: each decision's own order.sourced
        // event names only the group it (re)planned.
        eventStore.append("order", order, new NewEvent("order.sourced", Map.of("fulfilmentGroupIds", List.of(groupA), "decisionRecordId", decisionA), SYSTEM));
        eventStore.append("order", order, new NewEvent("order.sourced", Map.of("fulfilmentGroupIds", List.of(groupB), "decisionRecordId", decisionB), SYSTEM));

        Instant t = Instant.now();
        Episode epA = Episode.open(Ids.prefixed("epi"), decisionA, "sourcing_node_score", order, null, groupA, 1, "order", order, t);
        Episode epB = Episode.open(Ids.prefixed("epi"), decisionB, "sourcing_node_score", order, null, groupB, 1, "order", order, t);
        episodes.upsertDecisionPoint(epA);
        episodes.upsertDecisionPoint(epB);

        // One order.resourced rejects BOTH groups at once — ReshipAction.run()'s own nodeId==null branch for an
        // order with more than one active group. "reship" is nobody's refusal (nodeRejected false, A4-1).
        Event resourced = eventStore.append("order", order, new NewEvent("order.resourced",
            Map.of("reason", "reship", "nodeRejected", false, "rejectedNodes", List.of("DC-NJ", "3PL-ATL"),
                "rejectedGroupIds", List.of(groupA, groupB), "rejectedUnits", 2, "status", "SOURCED"), SYSTEM));
        observer.on(resourced);

        for (Episode ep : List.of(epA, epB)) {
            List<OutcomeRecord> outs = outcomes.forEpisode(ep.episodeId());
            assertThat(outs).as(ep.episodeId() + " (group=" + ep.fulfillmentGroupId() + ") must get a definite outcome, not stay open with none").isNotEmpty();
            assertThat(outs).allSatisfy(r -> assertThat(r.payload()).isInstanceOfSatisfying(OutcomeData.FulfillmentOutcome.class,
                f -> assertThat(f.storeRejected()).as("reship is nobody's refusal: no node blame").isFalse()));
        }

        rewards.sweepOnce();
        for (Episode ep : List.of(epA, epB)) {
            Episode scored = episodes.find(ep.episodeId()).orElseThrow();
            assertThat(scored.status()).as(ep.episodeId() + " must not be left OPEN forever").isNotEqualTo(EpisodeStatus.OPEN);
        }
    }
}
