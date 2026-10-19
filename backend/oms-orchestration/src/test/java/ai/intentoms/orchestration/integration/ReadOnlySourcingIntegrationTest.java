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
}
