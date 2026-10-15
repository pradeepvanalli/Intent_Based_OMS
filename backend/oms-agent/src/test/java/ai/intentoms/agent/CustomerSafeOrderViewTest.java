package ai.intentoms.agent;

import ai.intentoms.finance.model.Money;
import ai.intentoms.order.model.*;
import ai.intentoms.statemachine.model.EntityStatus;
import ai.intentoms.statemachine.model.QuantityBucket;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-AGT-01 / GR-15: the projection carries fulfilment facts and no economics or risk. */
class CustomerSafeOrderViewTest {
    @Test
    void projectionHasNoInternalKeys() {
        Instant now = Instant.parse("2026-10-05T12:00:00Z");
        OrderLine line = new OrderLine("L1", "TEE-BLK-M", "Black tee M", 2, Money.usd("28.00"), Money.zero("USD"), Money.usd("4.06"), "fg1", "released",
            Map.of("cogs", 7.5, "weight_kg", 0.25, "category", "apparel"));
        Totals totals = new Totals(Money.usd("56.00"), Money.zero("USD"), Money.usd("6.00"), Money.usd("4.06"), Money.zero("USD"), Money.zero("USD"), Money.usd("66.06"));
        MarginSnapshot margin = new MarginSnapshot(new BigDecimal("56.00"), new BigDecimal("30.00"), 0.53, 0.12, false, false, null, now);
        AgentProvenance prov = new AgentProvenance("shopbot", "mcp", "mnd_1", Money.usd("100"), "Buy tees", "sig", "buyer-1", true, "rec-1", Map.of("injectionScreen", Map.of("flagged", false)));
        Order order = new Order("ord_1", "agent", "AGT-1", "standard_ship", "buyer-1", new Customer("buyer-1", "Jane", "j@x.com", null, "gold", false, Map.of()), List.of(line),
            List.of(new Address("ship_to", "Jane", "1 Market St", null, "San Francisco", "CA", "94105", "US", null, "residential", true, null, null)), List.of(), List.of(), List.of(),
            List.of(new FulfilmentGroup("fg1", "dc-west", "ship_complete", List.of(new FulfilmentGroup.Allocation("L1", "TEE-BLK-M", 2)), LocalDate.of(2026, 10, 9), 0.94, "ground", Money.usd("5.10"), "planned", "plan-1", "rec-2",
                Map.of("cost_usd", 5.1, "margin", 0.5))), new IntentProfile("ship_complete", 0.9, "inferred", List.of(), "rec-3", true, null), prov, totals, margin,
            "order.standard_ship", 3, "released", "ground", "USD", Map.of(), now, now, now, 2);
        EntityStatus lineStatus = new EntityStatus("order_line", "ord_1:L1", "order_line.standard_ship", 3, "released", List.of(new QuantityBucket("released", 2, now)), Map.of());
        OrderRisk risk = new OrderRisk(3.2, "high", List.of("node_backlog"), "rec-4");
        ShipmentSummary ship = new ShipmentSummary("shp-1", "dc-west", "UPS", "ground", "1Z999", "in_transit", List.of(new ShipmentSummary.ShipmentLine("L1", 2)), now, null, now.plusSeconds(86400));
        ReturnSummary ret = new ReturnSummary("rma_1", "ord_1", "requested", now, "agent", 1, Money.usd("28.00"), 2.5, "on_inspection");
        OrderView view = new OrderView(order, new EntityStatus("order", "ord_1", "order.standard_ship", 3, "released", List.of(new QuantityBucket("released", 1, now)), Map.of()),
            List.of(lineStatus), order.fulfilmentGroups(), List.of(), List.of(ship), List.of(ret), List.of(), List.of(), List.of("rec-1"), List.of(), null, List.of(), List.of(), List.of(), risk, null);

        Map<String, Object> safe = CustomerSafeOrderView.order(view);

        assertThat(CustomerSafeOrderView.isCustomerSafe(safe)).as("no margin/risk/cost/cogs keys: " + safe).isTrue();
        assertThat(safe.toString()).doesNotContain("cogs").doesNotContain("marginRatio").doesNotContain("riskScore").doesNotContain("cost_usd").doesNotContain("dc-west");
        assertThat(safe).containsEntry("orderId", "ord_1").containsEntry("status", "released").containsKeys("lines", "totals", "shipments", "returns", "fulfilment", "agent");
        @SuppressWarnings("unchecked") Map<String, Object> l1 = ((List<Map<String, Object>>) safe.get("lines")).get(0);
        assertThat(l1).containsEntry("promiseDate", LocalDate.of(2026, 10, 9)).containsEntry("status", "released");
        assertThat(l1.get("quantities").toString()).contains("released");
        assertThat(l1.get("tracking").toString()).contains("1Z999");
        assertThat(l1).doesNotContainKey("attributes");
        @SuppressWarnings("unchecked") Map<String, Object> r = ((List<Map<String, Object>>) safe.get("returns")).get(0);
        assertThat(r).containsEntry("rma", "rma_1").doesNotContainKey("riskScore");
    }

    @Test
    void redactRemovesNestedInternalKeys() {
        Map<String, Object> redacted = CustomerSafeOrderView.redact(Map.of("status", "shipped", "margin", Map.of("ratio", 0.2), "plan", Map.of("cost_usd", 1, "eta", "2026-10-09"),
            "items", List.of(Map.of("sku", "A", "cogs", 2))));
        assertThat(CustomerSafeOrderView.isCustomerSafe(redacted)).isTrue();
        assertThat(redacted).containsEntry("status", "shipped").doesNotContainKey("margin");
        assertThat(redacted.get("plan")).isEqualTo(Map.of("eta", "2026-10-09"));
        assertThat(redacted.get("items")).isEqualTo(List.of(Map.of("sku", "A")));
    }
}
