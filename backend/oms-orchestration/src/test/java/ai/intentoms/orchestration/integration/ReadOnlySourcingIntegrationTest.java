package ai.intentoms.orchestration.integration;

import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.Mode;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.learning.model.Episode;
import ai.intentoms.learning.store.EpisodeRepository;
import ai.intentoms.orchestration.OrchestrationTestBase;
import ai.intentoms.orchestration.actions.RepromiseAction;
import ai.intentoms.orchestration.workflow.StandardShipOrderWorkflow;
import ai.intentoms.order.model.FulfilmentGroup;
import ai.intentoms.order.model.Order;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.sourcing.SourcingService;
import ai.intentoms.workflow.model.WorkflowStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E-1: reading sourcing options and re-promising score {@code sourcing_node_score} in SHADOW mode, so they never write
 * a LIVE record nor repoint the order's episode. OC-02: re-sourcing a rejected group is a decision about that group,
 * so it opens the group's episode instead of repointing the order's.
 */
class ReadOnlySourcingIntegrationTest extends OrchestrationTestBase {
    private static final String DECISION = "sourcing_node_score";

    @Autowired SourcingService sourcing;
    @Autowired DecisionRecordRepository records;
    @Autowired EpisodeRepository episodes;
    @Autowired RepromiseAction repromise;

    private Order sourcedOrder() {
        Order o = capture(webOrder("E1-" + Ids.shortId(), "tok_visa_ok"));
        driveToStep(StandardShipOrderWorkflow.TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_fulfilment");
        drainOutbox();
        return orders.get(o.id());
    }

    private List<DecisionRecord> scores(Order o, Mode mode) {
        return records.forSubject("order", o.id()).stream().filter(r -> DECISION.equals(r.decision()) && r.mode() == mode).toList();
    }

    private Episode orderEpisode(Order o) {
        return episodes.forOrder(o.id()).stream().filter(e -> DECISION.equals(e.decisionName()) && e.fulfillmentGroupId() == null).findFirst().orElseThrow();
    }

    @Test
    void readOnlyPathsScoreInShadowAndLeaveTheEpisodeAlone() {
        Order o = sourcedOrder();
        int live = scores(o, Mode.LIVE).size();
        assertThat(live).isPositive();
        Episode before = orderEpisode(o);

        assertThat(sourcing.options(o.id())).isNotEmpty();             // GET /orders/{id}/sourcing/options (also replay and simulations)
        repromise.repromise(o, "delay_notice", "cause_e1", "carrier delay"); // re-promise reads options to pick the new date
        drainOutbox();

        assertThat(scores(o, Mode.LIVE)).as("no LIVE record from a read-only path").hasSize(live);
        assertThat(scores(o, Mode.SHADOW)).as("scored in shadow, with the learned features, for the explain").hasSizeGreaterThanOrEqualTo(2);
        Episode after = orderEpisode(o);
        assertThat(after.lastDecisionId()).isEqualTo(before.lastDecisionId());
        assertThat(after.evaluationCount()).isEqualTo(before.evaluationCount());
    }

    @Test
    void reSourcingARejectedGroupOpensThatGroupsEpisode() {
        Order o = sourcedOrder();
        Episode before = orderEpisode(o);
        FulfilmentGroup group = o.fulfilmentGroups().get(0);

        sourcing.resource(o.id(), group.nodeId(), "store rejected the release", SYSTEM);
        drainOutbox();

        DecisionRecord resourced = scores(o, Mode.LIVE).stream().filter(r -> group.id().equals(r.input().get("fulfillmentGroupId"))).findFirst().orElseThrow();
        assertThat(episodes.forOrder(o.id())).anySatisfy(e -> {
            assertThat(e.decisionName()).isEqualTo(DECISION);
            assertThat(e.fulfillmentGroupId()).isEqualTo(group.id());
            assertThat(e.lastDecisionId()).isEqualTo(resourced.id());
        });
        assertThat(orderEpisode(o).lastDecisionId()).as("the order-level sourcing episode is not repointed").isEqualTo(before.lastDecisionId());
    }

    @Autowired ai.intentoms.learning.store.OutcomeRepository outcomes;

    private List<String> outcomeTypes(Episode e) {
        return outcomes.forEpisode(e.episodeId()).stream().map(r -> r.payload().getClass().getSimpleName()).toList();
    }

    /**
     * OC-02 as the audit reproduced it (A-N1): a store rejects, the workflow re-sources, the replacement ships and is
     * delivered. The re-sourcing decision chose the node that shipped, so its episode (keyed by the group it replaced)
     * earns the shipment and delivery; the original order-level choice, whose node refused, does not get the credit.
     */
    @Test
    void theReSourcingEpisodeEarnsTheReplacementsOutcomesAndTheFailedChoiceIsScoredOnItsRefusal() {
        Order o = sourcedOrder();
        Episode original = orderEpisode(o);
        FulfilmentGroup rejected = o.fulfilmentGroups().get(0);
        inbound.wms(rejected.nodeId(), wmsEvent("rej-" + o.id(), "store_rejected", o.id(), java.util.Map.of("reason", "no staff")));
        drive(StandardShipOrderWorkflow.TYPE, o.id(), w -> WorkflowStatus.WAITING_SIGNAL.equals(w.status()) && "await_fulfilment".equals(w.currentStep())
            && w.vars().get("cycle") instanceof Number n && n.intValue() >= 1);
        drainOutbox();
        List<FulfilmentGroup> replacements = orders.get(o.id()).fulfilmentGroups().stream()
            .filter(g -> !"rejected".equalsIgnoreCase(g.status()) && !"cancelled".equalsIgnoreCase(g.status())).toList();
        assertThat(replacements).isNotEmpty().noneMatch(g -> g.id().equals(rejected.id()));

        for (FulfilmentGroup g : replacements) {
            inbound.wms(g.nodeId(), wmsEvent("ship-" + g.id(), "shipped", o.id(), java.util.Map.of("fulfilmentGroupId", g.id(), "carrier", "UPS", "service", "ground", "tracking", "T-" + g.id())));
        }
        driveToStep(StandardShipOrderWorkflow.TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_delivery");
        for (FulfilmentGroup g : replacements) inbound.carrier(java.util.Map.of("eventId", "del-" + g.id(), "type", "delivered", "orderId", o.id(), "tracking", "T-" + g.id()));
        driveToTerminal(StandardShipOrderWorkflow.TYPE, o.id());
        drainOutbox();
        drainOutbox();

        Episode resourcing = episodes.forOrder(o.id()).stream().filter(e -> DECISION.equals(e.decisionName()) && rejected.id().equals(e.fulfillmentGroupId())).findFirst().orElseThrow();
        assertThat(outcomeTypes(resourcing)).as("outcomes of the re-sourcing episode").contains("FulfillmentOutcome", "DeliveryOutcome");
        // The original choice gets none of the replacement's shipments or deliveries; its one outcome is its node's refusal.
        assertThat(outcomeTypes(orderEpisode(o))).as("outcomes of the original, failed choice " + original.episodeId()).containsExactly("FulfillmentOutcome");
        assertThat(outcomes.forEpisode(orderEpisode(o).episodeId())).singleElement().satisfies(r -> assertThat(r.payload())
            .isInstanceOfSatisfying(ai.intentoms.learning.model.OutcomeData.FulfillmentOutcome.class, f -> {
                assertThat(f.storeRejected()).isTrue();
                assertThat(f.unitsShipped()).isZero();
                assertThat(f.fulfilmentGroupId()).isEqualTo(rejected.id());
            }));
    }
}
