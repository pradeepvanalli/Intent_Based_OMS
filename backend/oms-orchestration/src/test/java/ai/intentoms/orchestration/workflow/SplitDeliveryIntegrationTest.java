package ai.intentoms.orchestration.workflow;

import ai.intentoms.inventory.model.Atp;
import ai.intentoms.inventory.model.InventoryPosition;
import ai.intentoms.inventory.service.InventoryService;
import ai.intentoms.orchestration.OrchestrationTestBase;
import ai.intentoms.order.model.FulfilmentGroup;
import ai.intentoms.order.model.Order;
import ai.intentoms.platform.json.Json;
import ai.intentoms.workflow.model.WorkflowStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit B-2: every fulfilment group's last parcel produces an {@code order.delivered} with {@code groupDelivered=true},
 * which is what the node, lane, backlog and buffer learners credit. Two ways it was lost: a line split by quantity
 * across nodes (the group never counted as shipped), and two scans arriving before the workflow processed the first.
 */
class SplitDeliveryIntegrationTest extends OrchestrationTestBase {
    private static final String TYPE = StandardShipOrderWorkflow.TYPE;
    @Autowired InventoryService inventory;

    private List<Map<String, Object>> events(String orderId, String type) {
        return jdbc.sql("select payload::text as p from event where tenant_id = :t and aggregate_id = :o and type = :ty order by position").param("t", tenant).param("o", orderId).param("ty", type)
            .query((rs, i) -> Json.readMap(rs.getString("p"))).list();
    }

    /** Leaves {@code qty} sellable units of the sku at each named node and none anywhere else. */
    private void stockOnly(String sku, Map<String, Integer> qtyByNode) {
        List<InventoryPosition> positions = new ArrayList<>();
        for (Atp a : inventory.atp(sku)) positions.add(InventoryPosition.onHand(sku, a.nodeId(), qtyByNode.containsKey(a.nodeId()) ? qtyByNode.get(a.nodeId()) + a.safety() : 0));
        inventory.upsert(positions, "test");
    }

    private List<String> stockedNodes(String sku) {
        return inventory.atp(sku).stream().filter(a -> a.available() > 0).map(Atp::nodeId).sorted().toList();
    }

    private List<FulfilmentGroup> liveGroups(Order o) {
        return orders.get(o.id()).fulfilmentGroups().stream().filter(g -> !"rejected".equalsIgnoreCase(g.status()) && !"cancelled".equalsIgnoreCase(g.status())).toList();
    }

    /** The WMS ships exactly what the group was allocated, as one parcel. */
    private void shipGroup(Order o, FulfilmentGroup g, String tracking) {
        List<Map<String, Object>> lines = new ArrayList<>();
        Map<String, Integer> byLine = new LinkedHashMap<>();
        for (FulfilmentGroup.Allocation a : g.allocations()) byLine.merge(a.lineId(), a.qty(), Integer::sum);
        byLine.forEach((line, qty) -> lines.add(Map.of("lineId", line, "qty", qty)));
        inbound.wms(g.nodeId(), wmsEvent("ship-" + tracking, "shipped", o.id(), Map.of("fulfilmentGroupId", g.id(), "carrier", "UPS", "service", "ground", "tracking", tracking, "lines", lines)));
    }

    private void scan(Order o, String tracking) {
        inbound.carrier(Map.of("eventId", "del-" + tracking, "type", "delivered", "orderId", o.id(), "tracking", tracking));
    }

    private void assertEveryGroupCreditedOnce(Order o, List<FulfilmentGroup> groups) {
        List<Map<String, Object>> delivered = events(o.id(), "order.delivered");
        for (FulfilmentGroup g : groups) {
            assertThat(delivered).filteredOn(e -> g.id().equals(e.get("fulfilmentGroupId")) && Boolean.TRUE.equals(e.get("groupDelivered")))
                .as("groupDelivered=true events for group %s in %s", g.id(), delivered).hasSize(1);
        }
        assertThat(liveGroups(o)).allSatisfy(g -> assertThat(g.status()).isEqualToIgnoringCase("delivered"));
    }

    @Test
    void aLineSplitByQuantityAcrossNodesCreditsEachGroupWhenItsParcelArrives() {
        List<String> hoodieNodes = stockedNodes("HOOD-GRY-M");
        assertThat(hoodieNodes).hasSizeGreaterThanOrEqualTo(2);
        stockOnly("HOOD-GRY-M", Map.of(hoodieNodes.get(0), 1, hoodieNodes.get(1), 1));
        Order o = capture(webOrder("SPLIT-Q1", "tok_visa_ok"));
        driveToStep(TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_fulfilment");

        List<FulfilmentGroup> groups = liveGroups(o);
        List<FulfilmentGroup> hoodieGroups = groups.stream().filter(g -> g.allocations().stream().anyMatch(a -> "1".equals(a.lineId()))).toList();
        assertThat(hoodieGroups).as("line 1 (2 hoodies) split by quantity across two nodes").hasSize(2)
            .allSatisfy(g -> assertThat(g.allocations().stream().filter(a -> "1".equals(a.lineId())).mapToInt(FulfilmentGroup.Allocation::qty).sum()).isEqualTo(1));

        for (int i = 0; i < groups.size(); i++) {
            shipGroup(o, groups.get(i), "T-SQ-" + i);
            drive(TYPE, o.id(), w -> WorkflowStatus.WAITING_SIGNAL.equals(w.status()));
        }
        driveToStep(TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_delivery");
        assertThat(liveGroups(o)).allSatisfy(g -> assertThat(g.status()).isEqualToIgnoringCase("shipped"));

        for (int i = 0; i < groups.size(); i++) {
            scan(o, "T-SQ-" + i);
            int seen = i + 1;
            drive(TYPE, o.id(), w -> (WorkflowStatus.WAITING_SIGNAL.equals(w.status()) || WorkflowStatus.isTerminal(w.status())) && events(o.id(), "order.delivered").size() >= seen);
            String groupId = groups.get(i).id();
            assertThat(events(o.id(), "order.delivered")).filteredOn(e -> groupId.equals(e.get("fulfilmentGroupId"))).singleElement()
                .satisfies(e -> assertThat(e).containsEntry("groupDelivered", true));
        }
        driveToTerminal(TYPE, o.id());
        assertEveryGroupCreditedOnce(o, groups);
    }

    @Test
    void twoScansArrivingBeforeTheFirstIsProcessedCreditBothGroups() {
        List<String> hoodieNodes = stockedNodes("HOOD-GRY-M");
        String sneakerNode = stockedNodes("SNK-RUN-10").stream().filter(n -> !n.equals(hoodieNodes.get(0))).findFirst().orElseThrow();
        stockOnly("HOOD-GRY-M", Map.of(hoodieNodes.get(0), 5));
        stockOnly("SNK-RUN-10", Map.of(sneakerNode, 5));
        Order o = capture(webOrder("SPLIT-R1", "tok_visa_ok"));
        driveToStep(TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_fulfilment");
        List<FulfilmentGroup> groups = liveGroups(o);
        assertThat(groups).hasSize(2);

        for (int i = 0; i < groups.size(); i++) {
            shipGroup(o, groups.get(i), "T-SR-" + i);
            drive(TYPE, o.id(), w -> WorkflowStatus.WAITING_SIGNAL.equals(w.status()));
        }
        driveToStep(TYPE, o.id(), WorkflowStatus.WAITING_SIGNAL, "await_delivery");

        // Both scans reach the webhook (and the shipment records) before the workflow handles the first one.
        scan(o, "T-SR-0");
        scan(o, "T-SR-1");
        driveToTerminal(TYPE, o.id());

        assertThat(events(o.id(), "order.delivered")).hasSize(2);
        assertEveryGroupCreditedOnce(o, groups);
    }
}
