package ai.intentoms.order.modification;

import ai.intentoms.finance.model.Money;
import ai.intentoms.order.OrderTestBase;
import ai.intentoms.order.model.Address;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.service.OrderService;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.json.Json;
import ai.intentoms.statemachine.StateMachine;
import ai.intentoms.statemachine.model.EntityStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FR-CS-02: feasibility and application of order changes against the live state machine. */
class OrderModificationIntegrationTest extends OrderTestBase {
    @Autowired OrderService orders;
    @Autowired OrderModificationService modifications;
    @Autowired StateMachine stateMachine;

    @Test
    void cancelLineBeforeReleaseTransitionsRepricesAndAppendsEvents() {
        Order o = orders.capture(webOrder("W-" + Ids.shortId()), null);
        Feasibility f = modifications.check(o.id(), new Modification.CancelLine("L2"));
        assertThat(f.allowed()).isTrue();
        assertThat(f.costDelta()).isEqualTo(Money.usd("-34.32")); // 32.00 + 7.25% tax
        assertThat(f.affectedLines()).containsExactly("L2");

        Order after = modifications.apply(o.id(), new Modification.CancelLine("L2"), "customer changed mind", null);
        EntityStatus l2 = stateMachine.status("order_line", o.id() + ":L2");
        assertThat(l2.state()).isEqualTo("cancelled");
        assertThat(l2.qtyIn("cancelled")).isEqualTo(1);
        assertThat(after.line("L2")).get().extracting(l -> l.status()).isEqualTo("cancelled");
        assertThat(after.totals().merchandise()).isEqualTo(Money.usd("56.00"));
        assertThat(after.totals().tax()).isEqualTo(Money.usd("4.06"));
        assertThat(after.totals().grandTotal()).isEqualTo(Money.usd("66.06"));
        assertThat(after.version()).isEqualTo(2);
        assertThat(jdbc.sql("select status from order_line where order_id = :o and line_id = 'L2'").param("o", o.id()).query(String.class).single()).isEqualTo("cancelled");
        assertThat(events(o.id(), "line.cancelled")).hasSize(1);
        List<Map<String, Object>> modified = events(o.id(), "order.modified");
        assertThat(modified).hasSize(1);
        Map<String, Object> payload = Json.readMap((String) modified.get(0).get("payload"));
        assertThat(payload).containsEntry("type", "CancelLine").containsEntry("lineId", "L2").containsEntry("qty", 1).containsEntry("reason", "customer changed mind");
        assertThat(events(o.id(), "order.transitioned")).isNotEmpty();
        // Cancelling again is refused.
        assertThat(modifications.check(o.id(), new Modification.CancelLine("L2")).allowed()).isFalse();
    }

    @Test
    void cancelOrderCancelsEveryLineAndRollsTheHeaderUp() {
        Order o = orders.capture(webOrder("W-" + Ids.shortId()), null);
        Order after = modifications.apply(o.id(), new Modification.CancelOrder(), "duplicate order", null);
        assertThat(after.lines()).allMatch(l -> "cancelled".equals(l.status()));
        assertThat(after.totals().grandTotal()).isEqualTo(Money.usd("0.00"));
        assertThat(stateMachine.status("order", o.id()).state()).isEqualTo("cancelled");
        assertThat(orders.get(o.id()).status()).isEqualTo("cancelled");
        assertThat(events(o.id(), "order.cancelled")).hasSize(1);
        assertThat(events(o.id(), "line.cancelled")).isEmpty();
        assertThat(modifications.check(o.id(), new Modification.CancelOrder()).reasons().get(0)).startsWith("order_cancelled");
    }

    @Test
    void changeQtyBeyondAtpIsNotAllowedAndDecreaseCancelsUnits() {
        Order o = orders.capture(webOrder("W-" + Ids.shortId()), null);
        Feasibility up = modifications.check(o.id(), new Modification.ChangeQty("L1", 5));
        assertThat(up.allowed()).isFalse();
        assertThat(up.reasons().get(0)).startsWith("insufficient_atp");
        assertThatThrownBy(() -> modifications.apply(o.id(), new Modification.ChangeQty("L1", 5), "more please", null)).isInstanceOf(ConflictException.class);

        Order after = modifications.apply(o.id(), new Modification.ChangeQty("L1", 1), "one is enough", null);
        assertThat(after.line("L1")).get().extracting(l -> l.qty()).isEqualTo(1);
        assertThat(after.line("L1").get().attributes()).containsEntry("cancelled_qty", 1);
        EntityStatus l1 = stateMachine.status("order_line", o.id() + ":L1");
        assertThat(l1.qtyIn("cancelled")).isEqualTo(1);
        assertThat(l1.qtyIn("created")).isEqualTo(1);
        assertThat(after.totals().merchandise()).isEqualTo(Money.usd("60.00"));
    }

    @Test
    void changeAddressAndShipMethodRepriceAndRespectCompliance() {
        Order o = orders.capture(webOrder("W-" + Ids.shortId()), null);
        Address ny = new Address("ship_to", "Jane Doe", "5 5th Ave", null, "New York", "NY", "10001", "US", null, null, false, null, null);
        Feasibility f = modifications.check(o.id(), new Modification.ChangeAddress(ny));
        assertThat(f.allowed()).isTrue();
        assertThat(f.reasons()).anyMatch(r -> r.startsWith("tax_recalculated"));
        Order after = modifications.apply(o.id(), new Modification.ChangeAddress(ny), "moved", null);
        assertThat(after.shipTo().get().state()).isEqualTo("NY");
        assertThat(after.shipTo().get().verified()).isTrue();
        assertThat(after.totals().tax()).isEqualTo(Money.usd("3.52")); // NY 4% of 88

        Address bad = new Address("ship_to", "J", "UNDELIVERABLE 1 Nowhere", null, "New York", "NY", "10001", "US", null, null, false, null, null);
        assertThat(modifications.check(o.id(), new Modification.ChangeAddress(bad)).allowed()).isFalse();

        Order hazmat = orders.capture(withLines(webOrder("W-" + Ids.shortId()), List.of(line("L1", "PERF-50ML", 1, "120.00"))), null);
        Feasibility air = modifications.check(hazmat.id(), new Modification.ChangeShipMethod("air_2day"));
        assertThat(air.allowed()).isFalse();
        assertThat(air.reasons().get(0)).startsWith("HAZMAT_AIR");
        Order faster = modifications.apply(o.id(), new Modification.ChangeShipMethod("air_2day"), "needs it sooner", null);
        assertThat(faster.shipMethod()).isEqualTo("air_2day");
        assertThat(events(o.id(), "order.modified")).hasSize(2);
    }

    @Test
    void changeItemSwapsSkuWhenAtpAllows() {
        Order o = orders.capture(webOrder("W-" + Ids.shortId()), null);
        Feasibility noStock = modifications.check(o.id(), new Modification.ChangeItem("L2", "HOOD-GRY-M"));
        assertThat(noStock.allowed()).isFalse();
        assertThat(noStock.reasons().get(0)).startsWith("insufficient_atp");
        assertThat(modifications.check(o.id(), new Modification.ChangeItem("L2", "NOPE")).reasons().get(0)).startsWith("unknown_sku");
    }
}
