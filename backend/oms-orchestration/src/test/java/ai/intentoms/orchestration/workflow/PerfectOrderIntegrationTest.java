package ai.intentoms.orchestration.workflow;

import ai.intentoms.learning.model.Episode;
import ai.intentoms.learning.service.RewardCalculator;
import ai.intentoms.learning.service.RewardMaturityService;
import ai.intentoms.learning.store.EpisodeRepository;
import ai.intentoms.orchestration.OrchestrationTestBase;
import ai.intentoms.order.contact.CustomerContactService;
import ai.intentoms.order.model.Order;
import ai.intentoms.workflow.model.WorkflowStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §12 north star through the real workflow: the order-level episode's {@code perfect} component is absent while the
 * order is on its way, 1 once it is delivered complete, on time, from its first sourcing, and corrected to 0 by a later
 * complaint. It carries no weight in the reward.
 */
class PerfectOrderIntegrationTest extends OrchestrationTestBase {
    private static final String TYPE = StandardShipOrderWorkflow.TYPE;
    @Autowired EpisodeRepository episodes;
    @Autowired RewardMaturityService rewards;
    @Autowired CustomerContactService contacts;

    private Episode orderEpisode(Order o) {
        drainOutbox();
        rewards.sweepOnce();
        Episode ep = episodes.forOrder(o.id()).stream().filter(e -> "sourcing_node_score".equals(e.decisionName()) && e.fulfillmentGroupId() == null && e.lineId() == null)
            .findFirst().orElseThrow();
        return episodes.find(ep.episodeId()).orElseThrow();
    }

    @Test
    void aDeliveredOrderIsPerfectUntilTheCustomerComplains() {
        Order o = capture(webOrder("PERFECT-1", "tok_visa_ok"));
        driveToStep(TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_fulfilment");
        inbound.wms(nodeOf(o), wmsEvent("ship-PERFECT-1", "shipped", o.id(), Map.of("carrier", "UPS", "tracking", "T-PERFECT-1")));
        driveToStep(TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_delivery");
        assertThat(orderEpisode(o).rewardComponents()).as("shipped, not delivered").doesNotContainKey(RewardCalculator.PERFECT);

        inbound.carrier(Map.of("eventId", "del-PERFECT-1", "type", "delivered", "orderId", o.id(), "tracking", "T-PERFECT-1"));
        driveToTerminal(TYPE, o.id());
        Episode delivered = orderEpisode(o);
        assertThat(delivered.rewardComponents()).containsEntry(RewardCalculator.PERFECT, 1.0).containsEntry("fill_first_attempt", 1.0);
        double reward = delivered.reward();

        contacts.record(o.id(), new CustomerContactService.Contact("complaint", "email", "test", "ticket-perfect-1"));
        Episode complained = orderEpisode(o);
        assertThat(complained.rewardComponents()).containsEntry(RewardCalculator.PERFECT, 0.0);
        assertThat(complained.reward()).as("the complaint moves the reward through cx only").isLessThan(reward);
    }
}
