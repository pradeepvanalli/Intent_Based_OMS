package ai.intentoms.orchestration.workflow;

import ai.intentoms.learning.model.Episode;
import ai.intentoms.learning.model.OutcomeData;
import ai.intentoms.learning.model.OutcomeRecord;
import ai.intentoms.learning.service.RewardMaturityService;
import ai.intentoms.learning.store.EpisodeRepository;
import ai.intentoms.learning.store.OutcomeRepository;
import ai.intentoms.orchestration.OrchestrationTestBase;
import ai.intentoms.order.model.Order;
import ai.intentoms.platform.json.Json;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * A return's events through the real return workflow (audit A2 and A4): a wrong-item return that is refunded and then
 * dispositioned twice (automatically, then by a person) is one ReturnOutcome per episode, its loss counted once as the
 * refund over the order's value, also on the return's own episodes, which were opened after shipment.
 */
class ReturnOutcomeIntegrationTest extends OrchestrationTestBase {
    @Autowired ReturnService returns;
    @Autowired EpisodeRepository episodes;
    @Autowired OutcomeRepository outcomes;
    @Autowired RewardMaturityService rewards;

    private Order deliveredOrder(String ref) {
        Order o = capture(webOrder(ref, "tok_visa_ok"));
        driveToStep(StandardShipOrderWorkflow.TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_fulfilment");
        inbound.wms(nodeOf(o), wmsEvent(ref + "-ship", "shipped", o.id(), Map.of("carrier", "UPS", "service", "ground", "tracking", "TRK-" + ref)));
        driveToStep(StandardShipOrderWorkflow.TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_delivery");
        inbound.carrier(Map.of("eventId", ref + "-del", "type", "delivered", "orderId", o.id(), "tracking", "TRK-" + ref));
        driveToTerminal(StandardShipOrderWorkflow.TYPE, o.id());
        return orders.get(o.id());
    }

    private List<String> returnEventTypes(String rmaId) {
        return jdbc.sql("select type from event where tenant_id = :t and aggregate_id = :r order by position").param("t", tenant).param("r", rmaId).query(String.class).list();
    }

    private static List<OutcomeData.ReturnOutcome> returnOutcomes(List<OutcomeRecord> outs) {
        return outs.stream().map(OutcomeRecord::payload).filter(OutcomeData.ReturnOutcome.class::isInstance).map(OutcomeData.ReturnOutcome.class::cast).toList();
    }

    @Test
    void aWrongItemReturnRefundedAndDispositionedTwiceIsOneLossKeyedByRma() {
        Order o = deliveredOrder("RET-A2");
        double orderValue = o.totals().grandTotal().amount().doubleValue();
        ReturnRma rma = returns.create(new ReturnRequest(o.id(), List.of(new ReturnLineRequest("1", 1, null, "not as described")), ReturnChannel.WEB, null, null, false, null, null), SYSTEM);
        drainOutbox();
        drive(ReturnWorkflow.TYPE, rma.rmaId(), x -> WorkflowStatus.WAITING_TASK.equals(x.status()));
        tasks.complete(returns.getRma(rma.rmaId()).reviewTaskId(), RefundTiming.ON_INSPECTION, "refund once inspected", Map.of());
        drive(ReturnWorkflow.TYPE, rma.rmaId(), x -> WorkflowStatus.WAITING_SIGNAL.equals(x.status()) && ReturnStatus.AWAITING_RECEIPT.equals(returns.getRma(rma.rmaId()).status()));
        inbound.wms("DC-NJ", Map.of("eventId", "a2-recv", "type", "received", "rmaId", rma.rmaId()));
        drive(ReturnWorkflow.TYPE, rma.rmaId(), x -> WorkflowStatus.WAITING_SIGNAL.equals(x.status()) && engine.timeline(x.id()).stream().anyMatch(t -> "received".equals(t.name()) && "consumed".equals(t.status())));
        inbound.wms("DC-NJ", Map.of("eventId", "a2-insp", "type", "inspected", "rmaId", rma.rmaId(), "condition", "wrong_item", "note", "a different hoodie came back"));
        drive(ReturnWorkflow.TYPE, rma.rmaId(), x -> WorkflowStatus.isTerminal(x.status()) || WorkflowStatus.WAITING_TASK.equals(x.status()) || returns.getRma(rma.rmaId()).refunded());
        ReturnRma inspected = returns.getRma(rma.rmaId());
        if (!inspected.refunded()) returns.refund(rma.rmaId(), SYSTEM);
        // A person sets the disposition after the automatic one.
        returns.applyDisposition(rma.rmaId(), "destroy", "wrong item, not resaleable", SYSTEM);
        drainOutbox();
        drainOutbox();

        ReturnRma done = returns.getRma(rma.rmaId());
        assertThat(done.refunded()).isTrue();
        double refund = done.refund().amount().doubleValue();
        assertThat(returnEventTypes(rma.rmaId())).as("the producer emits several outcome events for one return")
            .filteredOn(t -> t.equals("return.dispositioned") || t.equals("return.refunded")).hasSizeGreaterThanOrEqualTo(3);

        List<Episode> touched = new java.util.ArrayList<>(episodes.forOrder(o.id()));
        touched.addAll(episodes.forSubject("return", rma.rmaId()));
        Map<String, Episode> byId = new LinkedHashMap<>();
        touched.forEach(e -> byId.put(e.episodeId(), e));
        assertThat(byId.values()).anyMatch(e -> "sourcing_node_score".equals(e.decisionName()))
            .as("an episode opened after shipment").anyMatch(e -> e.decisionName().startsWith("return_"));
        for (Episode ep : byId.values()) {
            List<OutcomeData.ReturnOutcome> rs = returnOutcomes(outcomes.forEpisode(ep.episodeId()));
            if (rs.isEmpty()) continue;
            assertThat(rs).as("episode %s (%s): %s", ep.episodeId(), ep.decisionName(), Json.write(rs)).singleElement().satisfies(r -> {
                assertThat(r.rmaId()).isEqualTo(rma.rmaId());
                assertThat(r.fraudConfirmed()).isTrue();
                assertThat(r.refunded()).isTrue();
                assertThat(r.refundAmount()).isCloseTo(refund, within(1e-6));
                assertThat(r.disposition()).isEqualTo("destroy");
            });
        }

        rewards.sweepOnce();
        double expected = Math.min(1.0, refund / orderValue);
        assertThat(expected).isLessThan(1.0);
        for (Episode ep : byId.values()) {
            if (returnOutcomes(outcomes.forEpisode(ep.episodeId())).isEmpty()) continue;
            Episode scored = episodes.find(ep.episodeId()).orElseThrow();
            Double fraud = scored.rewardComponents().getOrDefault("fraud_raw", scored.rewardComponents().get("fraud"));
            assertThat(fraud).as("fraud loss on %s (%s), opened %s", scored.decisionName(), scored.episodeId(), scored.createdAt()).isCloseTo(expected, within(1e-6));
        }
    }
}
