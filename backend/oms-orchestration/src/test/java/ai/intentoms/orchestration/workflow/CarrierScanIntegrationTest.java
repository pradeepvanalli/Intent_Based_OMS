package ai.intentoms.orchestration.workflow;

import ai.intentoms.learning.model.Episode;
import ai.intentoms.learning.model.OutcomeData;
import ai.intentoms.learning.model.OutcomeRecord;
import ai.intentoms.learning.service.RewardCalculator;
import ai.intentoms.learning.store.EpisodeRepository;
import ai.intentoms.learning.store.OutcomeRepository;
import ai.intentoms.orchestration.OrchestrationTestBase;
import ai.intentoms.orchestration.shipment.ShipmentRecord;
import ai.intentoms.order.model.Order;
import ai.intentoms.platform.json.Json;
import ai.intentoms.workflow.model.WorkflowStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Carrier scans through the real webhook: a scan whose tracking number matches no parcel moves nothing (audit A5), and
 * a parcel the carrier reports lost is a delivery outcome for the order's decisions (audit A6).
 */
class CarrierScanIntegrationTest extends OrchestrationTestBase {
    private static final String TYPE = StandardShipOrderWorkflow.TYPE;
    @Autowired EpisodeRepository episodes;
    @Autowired OutcomeRepository outcomes;

    private List<Map<String, Object>> events(String orderId, String type) {
        return jdbc.sql("select payload::text as p from event where tenant_id = :t and aggregate_id = :o and type = :ty order by position").param("t", tenant).param("o", orderId).param("ty", type)
            .query((rs, i) -> Json.readMap(rs.getString("p"))).list();
    }

    private Order shippedOrder(String ref, String tracking) {
        Order o = capture(webOrder(ref, "tok_visa_ok"));
        driveToStep(TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_fulfilment");
        inbound.wms(nodeOf(o), wmsEvent("ship-" + tracking, "shipped", o.id(), Map.of("carrier", "UPS", "tracking", tracking)));
        drive(TYPE, o.id(), w -> WorkflowStatus.WAITING_SIGNAL.equals(w.status()));
        return o;
    }

    private void assertNothingDelivered(Order o) {
        assertThat(events(o.id(), "order.delivered")).isEmpty();
        assertThat(lineStates(orders.get(o.id())).values()).doesNotContain("delivered");
        assertThat(shipments.forOrder(o.id())).extracting(ShipmentRecord::status).doesNotContain("delivered");
    }

    @Test
    void aScanWhoseTrackingMatchesNoParcelDeliversNothing() {
        Order o = shippedOrder("SCAN-A5", "T-A5-REAL");
        driveToStep(TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_delivery");

        Map<String, Object> out = inbound.carrier(Map.of("eventId", "del-A5-wrong", "type", "delivered", "orderId", o.id(), "tracking", "T-A5-SOMEONE-ELSE"));
        assertThat((List<?>) out.get("shipments")).isEmpty();
        assertThat((List<?>) out.get("orders")).isEmpty();
        assertThat(out).containsKey("ignored");
        drive(TYPE, o.id(), w -> WorkflowStatus.WAITING_SIGNAL.equals(w.status()));
        assertNothingDelivered(o);
        assertThat(engine.find(TYPE, o.id()).orElseThrow().currentStep()).isEqualTo("await_delivery");

        // The right scan still delivers.
        inbound.carrier(Map.of("eventId", "del-A5-real", "type", "delivered", "orderId", o.id(), "tracking", "T-A5-REAL"));
        driveToTerminal(TYPE, o.id());
        assertThat(orders.get(o.id()).status()).isEqualTo("delivered");
    }

    @Test
    void aScanBufferedBeforeTheParcelsExistDeliversNothingWhenItsTrackingMatchesNone() {
        Order o = capture(webOrder("SCAN-A5-BUF", "tok_visa_ok"));
        driveToStep(TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_fulfilment");
        // No parcel exists yet, so the webhook cannot tell the tracking is wrong: the signal waits in the workflow queue.
        inbound.carrier(Map.of("eventId", "del-A5-early", "type", "delivered", "orderId", o.id(), "tracking", "T-A5-NOT-OURS"));
        inbound.wms(nodeOf(o), wmsEvent("ship-A5-BUF", "shipped", o.id(), Map.of("carrier", "UPS", "tracking", "T-A5-BUF")));
        drive(TYPE, o.id(), w -> WorkflowStatus.isTerminal(w.status()) || (WorkflowStatus.WAITING_SIGNAL.equals(w.status()) && "await_delivery".equals(w.currentStep())
            && w.vars().get("signals") instanceof Number n && n.intValue() >= 2));
        assertNothingDelivered(o);
    }

    @Test
    void aParcelReportedLostIsADeliveryOutcomeForTheOrdersDecisions() {
        Order o = shippedOrder("SCAN-A6", "T-A6");
        driveToStep(TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_delivery");

        inbound.carrier(Map.of("eventId", "exc-A6", "type", "exception", "orderId", o.id(), "tracking", "T-A6", "condition", "lost"));
        drive(TYPE, o.id(), w -> WorkflowStatus.WAITING_SIGNAL.equals(w.status()) && !events(o.id(), "order.carrier_exception").isEmpty());
        assertThat(events(o.id(), "order.carrier_exception")).singleElement().satisfies(e -> {
            assertThat(e).containsEntry("lost", true).containsEntry("damaged", false).containsKey("fulfilmentGroupId");
            assertThat((List<?>) e.get("shipments")).hasSize(1);
        });
        drainOutbox();

        Episode sourcing = episodes.forOrder(o.id()).stream().filter(e -> "sourcing_node_score".equals(e.decisionName())).findFirst().orElseThrow();
        List<OutcomeRecord> recorded = outcomes.forEpisode(sourcing.episodeId());
        assertThat(recorded).extracting(OutcomeRecord::payload).filteredOn(OutcomeData.DeliveryOutcome.class::isInstance).singleElement()
            .isEqualTo(new OutcomeData.DeliveryOutcome(false, 0, false, true));
        assertThat(RewardCalculator.scoredTerms(recorded)).contains("otd");
        assertThat(RewardCalculator.components(recorded).get("otd")).isZero();
    }

    @Test
    void aDelayExceptionIsNotAnOutcome() {
        Order o = shippedOrder("SCAN-A6-DELAY", "T-A6-D");
        driveToStep(TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_delivery");
        inbound.carrier(Map.of("eventId", "exc-A6-delay", "type", "exception", "orderId", o.id(), "tracking", "T-A6-D"));
        drive(TYPE, o.id(), w -> WorkflowStatus.WAITING_SIGNAL.equals(w.status()) && !events(o.id(), "order.carrier_exception").isEmpty());
        drainOutbox();
        for (Episode ep : episodes.forOrder(o.id()))
            assertThat(outcomes.forEpisode(ep.episodeId())).extracting(OutcomeRecord::payload).noneMatch(OutcomeData.DeliveryOutcome.class::isInstance);
    }
}
