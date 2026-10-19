package ai.intentoms.orchestration.workflow;

import ai.intentoms.learning.model.Episode;
import ai.intentoms.learning.service.RewardCalculator;
import ai.intentoms.learning.service.RewardMaturityService;
import ai.intentoms.learning.store.EpisodeRepository;
import ai.intentoms.orchestration.OrchestrationTestBase;
import ai.intentoms.order.contact.CustomerContactService;
import ai.intentoms.order.model.Order;
import ai.intentoms.returns.model.RefundTiming;
import ai.intentoms.returns.model.ReturnChannel;
import ai.intentoms.returns.model.ReturnLineRequest;
import ai.intentoms.returns.model.ReturnRequest;
import ai.intentoms.returns.model.ReturnRma;
import ai.intentoms.returns.model.ReturnStatus;
import ai.intentoms.returns.service.ReturnService;
import ai.intentoms.workflow.model.WorkflowStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §12 north star on the §2 definition, through the real workflow (audit A-N3): "lines delivered complete, on time,
 * undamaged, not returned for fulfilment reasons". The order-level episode's {@code perfect} component is absent while
 * the order is on its way and judged per line once it is delivered ({@code perfect_lines} is the share of perfect lines,
 * {@code perfect} is 1 only when every line is). A WISMO call or a complaint does not make a delivered order imperfect
 * (they are the cx term); a line returned damaged makes that line, and so the order, imperfect. None of it is weighted.
 */
class PerfectOrderIntegrationTest extends OrchestrationTestBase {
    private static final String TYPE = StandardShipOrderWorkflow.TYPE;
    @Autowired EpisodeRepository episodes;
    @Autowired RewardMaturityService rewards;
    @Autowired CustomerContactService contacts;
    @Autowired ReturnService returns;

    private Episode orderEpisode(Order o) {
        drainOutbox();
        rewards.sweepOnce();
        Episode ep = episodes.forOrder(o.id()).stream().filter(e -> "sourcing_node_score".equals(e.decisionName()) && e.fulfillmentGroupId() == null && e.lineId() == null)
            .findFirst().orElseThrow();
        return episodes.find(ep.episodeId()).orElseThrow();
    }

    private Order deliver(String ref) {
        Order o = capture(webOrder(ref, "tok_visa_ok"));
        driveToStep(TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_fulfilment");
        inbound.wms(nodeOf(o), wmsEvent("ship-" + ref, "shipped", o.id(), Map.of("carrier", "UPS", "tracking", "T-" + ref)));
        driveToStep(TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_delivery");
        assertThat(orderEpisode(o).rewardComponents()).as("shipped, not delivered").doesNotContainKey(RewardCalculator.PERFECT);
        inbound.carrier(Map.of("eventId", "del-" + ref, "type", "delivered", "orderId", o.id(), "tracking", "T-" + ref));
        driveToTerminal(TYPE, o.id());
        return orders.get(o.id());
    }

    @Test
    void aDeliveredOrderStaysPerfectAfterAWismoCallAndAComplaint() {
        Order o = deliver("PERFECT-1");
        Episode delivered = orderEpisode(o);
        assertThat(delivered.rewardComponents()).containsEntry(RewardCalculator.PERFECT, 1.0).containsEntry(RewardCalculator.PERFECT_LINES, 1.0)
            .containsEntry(RewardCalculator.LINES, 2.0).containsEntry("fill", 1.0);
        double reward = delivered.reward();

        contacts.record(o.id(), new CustomerContactService.Contact("wismo", "phone", "test", "ticket-perfect-1a"));
        contacts.record(o.id(), new CustomerContactService.Contact("complaint", "email", "test", "ticket-perfect-1b"));
        Episode contacted = orderEpisode(o);
        assertThat(contacted.rewardComponents()).as("contacts are the cx term, not the north star")
            .containsEntry(RewardCalculator.PERFECT, 1.0).containsEntry(RewardCalculator.PERFECT_LINES, 1.0);
        assertThat(contacted.reward()).as("the contacts move the reward through cx only").isLessThan(reward);
    }

    @Test
    void aLineReturnedDamagedIsImperfectAndTheOtherLineIsNot() {
        Order o = deliver("PERFECT-2");
        assertThat(orderEpisode(o).rewardComponents()).containsEntry(RewardCalculator.PERFECT_LINES, 1.0);

        // Line 1 (the hoodies) arrived damaged in transit: the customer says so and inspection grades it damaged.
        ReturnRma rma = returns.create(new ReturnRequest(o.id(), List.of(new ReturnLineRequest("1", 1, "defective", "box crushed, arrived damaged")), ReturnChannel.WEB,
            null, null, false, null, null), SYSTEM);
        drainOutbox();
        drive(ReturnWorkflow.TYPE, rma.rmaId(), x -> WorkflowStatus.WAITING_TASK.equals(x.status()) || WorkflowStatus.WAITING_SIGNAL.equals(x.status()));
        ReturnRma created = returns.getRma(rma.rmaId());
        if (created.reviewTaskId() != null && !ReturnStatus.AWAITING_RECEIPT.equals(created.status())) tasks.complete(created.reviewTaskId(), RefundTiming.ON_INSPECTION, "refund once inspected", Map.of());
        drive(ReturnWorkflow.TYPE, rma.rmaId(), x -> WorkflowStatus.WAITING_SIGNAL.equals(x.status()) && ReturnStatus.AWAITING_RECEIPT.equals(returns.getRma(rma.rmaId()).status()));
        inbound.wms("DC-NJ", Map.of("eventId", "p2-recv", "type", "received", "rmaId", rma.rmaId()));
        drive(ReturnWorkflow.TYPE, rma.rmaId(), x -> WorkflowStatus.WAITING_SIGNAL.equals(x.status()) && engine.timeline(x.id()).stream().anyMatch(t -> "received".equals(t.name()) && "consumed".equals(t.status())));
        inbound.wms("DC-NJ", Map.of("eventId", "p2-insp", "type", "inspected", "rmaId", rma.rmaId(), "condition", "damaged", "note", "crushed in transit"));
        drive(ReturnWorkflow.TYPE, rma.rmaId(), x -> WorkflowStatus.isTerminal(x.status()) || WorkflowStatus.WAITING_TASK.equals(x.status()) || returns.getRma(rma.rmaId()).refunded());
        if (!returns.getRma(rma.rmaId()).refunded()) returns.refund(rma.rmaId(), SYSTEM);
        drainOutbox();

        Episode afterReturn = orderEpisode(o);
        assertThat(afterReturn.rewardComponents()).as("one of two lines came back for a fulfilment reason")
            .containsEntry(RewardCalculator.PERFECT_LINES, 0.5).containsEntry(RewardCalculator.LINES, 2.0).containsEntry(RewardCalculator.PERFECT, 0.0);
        assertThat(afterReturn.rewardComponents().get("fraud")).as("an honest damaged return is no fraud loss").isZero();
    }
}
