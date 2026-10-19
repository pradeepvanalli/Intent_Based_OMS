package ai.intentoms.orchestration.workflow;

import ai.intentoms.inventory.model.Atp;
import ai.intentoms.inventory.model.InventoryPosition;
import ai.intentoms.inventory.service.InventoryService;
import ai.intentoms.learning.model.Episode;
import ai.intentoms.learning.service.RewardMaturityService;
import ai.intentoms.learning.store.EpisodeRepository;
import ai.intentoms.orchestration.OrchestrationTestBase;
import ai.intentoms.order.model.FulfilmentGroup;
import ai.intentoms.order.model.Order;
import ai.intentoms.platform.json.Json;
import ai.intentoms.workflow.model.WorkflowStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * §7 fill through the real workflow: an order-level episode's fill counts every unit ordered, so a backordered line
 * counts against it, and {@code fill} counts only what the order's first sourcing shipped.
 */
class FillRateIntegrationTest extends OrchestrationTestBase {
    private static final String TYPE = StandardShipOrderWorkflow.TYPE;
    @Autowired InventoryService inventory;
    @Autowired EpisodeRepository episodes;
    @Autowired RewardMaturityService rewards;

    private List<Map<String, Object>> events(String orderId, String type) {
        return jdbc.sql("select payload::text as p from event where tenant_id = :t and aggregate_id = :o and type = :ty order by position").param("t", tenant).param("o", orderId).param("ty", type)
            .query((rs, i) -> Json.readMap(rs.getString("p"))).list();
    }

    private Episode scoredSourcingEpisode(Order o) {
        drainOutbox();
        rewards.sweepOnce();
        Episode ep = episodes.forOrder(o.id()).stream().filter(e -> "sourcing_node_score".equals(e.decisionName()) && e.fulfillmentGroupId() == null && e.lineId() == null)
            .findFirst().orElseThrow();
        return episodes.find(ep.episodeId()).orElseThrow();
    }

    private void ship(Order o, FulfilmentGroup g, String tracking) {
        List<Map<String, Object>> lines = new ArrayList<>();
        for (FulfilmentGroup.Allocation a : g.allocations()) lines.add(Map.of("lineId", a.lineId(), "qty", a.qty()));
        inbound.wms(g.nodeId(), wmsEvent("ship-" + tracking, "shipped", o.id(), Map.of("fulfilmentGroupId", g.id(), "carrier", "UPS", "tracking", tracking, "lines", lines)));
    }

    private List<FulfilmentGroup> live(Order o) {
        return orders.get(o.id()).fulfilmentGroups().stream().filter(g -> !"rejected".equalsIgnoreCase(g.status()) && !"cancelled".equalsIgnoreCase(g.status())).toList();
    }

    @Test
    void aBackorderedLineCountsInTheOrderLevelFillDenominator() {
        List<InventoryPosition> none = new ArrayList<>();
        for (Atp a : inventory.atp("SNK-RUN-10")) none.add(InventoryPosition.onHand("SNK-RUN-10", a.nodeId(), 0));
        inventory.upsert(none, "test");
        Order o = capture(webOrder("FILL-BO", "tok_visa_ok"));   // 2 hoodies + 1 sneaker, no sneaker anywhere
        drive(TYPE, o.id(), w -> WorkflowStatus.WAITING_SIGNAL.equals(w.status()) && !live(o).isEmpty());
        assertThat(lineStates(orders.get(o.id())).get("2")).as("the sneaker is in no group").isIn("backordered", "awaiting_sourcing");
        FulfilmentGroup hoodies = live(o).get(0);
        ship(o, hoodies, "T-FILL-BO");
        drive(TYPE, o.id(), w -> WorkflowStatus.WAITING_SIGNAL.equals(w.status()) && !events(o.id(), "order.shipped").isEmpty());
        assertThat(events(o.id(), "order.shipped")).singleElement().satisfies(e -> assertThat(e).containsEntry("orderUnits", 3).containsEntry("unitsShipped", 2).containsEntry("firstAttempt", true));

        Episode ep = scoredSourcingEpisode(o);
        assertThat(ep.rewardComponents().get("fill")).as("2 of the 3 units ordered shipped").isCloseTo(2.0 / 3.0, within(1e-9));
        assertThat(ep.rewardComponents().get("fill_all_attempts")).isCloseTo(2.0 / 3.0, within(1e-9));
    }

    @Test
    void unitsShippedAfterAReSourcingAreNotFirstAttemptFill() {
        Order o = capture(webOrder("FILL-REJ", "tok_visa_ok"));
        driveToStep(TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_fulfilment");
        String first = nodeOf(o);
        inbound.wms(first, wmsEvent("rej-FILL", "store_rejected", o.id(), Map.of("reason", "no staff")));
        drive(TYPE, o.id(), w -> WorkflowStatus.WAITING_SIGNAL.equals(w.status()) && "await_fulfilment".equals(w.currentStep())
            && w.vars().get("cycle") instanceof Number n && n.intValue() >= 1);
        List<FulfilmentGroup> groups = live(o);
        assertThat(groups).isNotEmpty().noneMatch(g -> g.nodeId().equals(first));
        for (int i = 0; i < groups.size(); i++) {
            ship(o, groups.get(i), "T-FILL-REJ-" + i);
            drive(TYPE, o.id(), w -> WorkflowStatus.WAITING_SIGNAL.equals(w.status()));
        }
        assertThat(events(o.id(), "order.shipped")).isNotEmpty().allSatisfy(e -> assertThat(e).containsEntry("firstAttempt", false));

        // OC-02 (A-N1): the shipments belong to the re-sourcing decision that planned them, keyed by the rejected group;
        // the original choice, whose node refused, does not get their credit.
        drainOutbox();
        rewards.sweepOnce();
        Episode ep = episodes.forOrder(o.id()).stream().filter(e -> "sourcing_node_score".equals(e.decisionName()) && e.fulfillmentGroupId() != null)
            .map(e -> episodes.find(e.episodeId()).orElseThrow()).findFirst().orElseThrow();
        // §2 FillRate is the first attempt: the weighted fill term is 0 although every unit shipped from the second sourcing.
        assertThat(ep.rewardComponents().get("fill")).as("everything shipped from the second sourcing").isZero();
        assertThat(ep.rewardComponents().get("fill_all_attempts")).as("every attempt, unweighted").isCloseTo(1.0, within(1e-9));
        Episode original = episodes.forOrder(o.id()).stream().filter(e -> "sourcing_node_score".equals(e.decisionName()) && e.fulfillmentGroupId() == null).findFirst().orElseThrow();
        assertThat(episodes.find(original.episodeId()).orElseThrow().reward()).as("the failed first choice earns no shipment credit").isNull();
    }
}
