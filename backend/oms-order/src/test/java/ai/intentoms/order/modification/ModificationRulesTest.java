package ai.intentoms.order.modification;

import ai.intentoms.finance.model.Money;
import ai.intentoms.order.model.Address;
import ai.intentoms.order.model.MarginSnapshot;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.model.OrderLine;
import ai.intentoms.order.model.Totals;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-CS-02 feasibility rules over fake quantity statuses. */
class ModificationRulesTest {
    static final Set<String> CANCELLABLE = Set.of("created", "awaiting_sourcing", "released", "backordered");

    static OrderLine line(String id, String sku, int qty, String price, Map<String, Object> attrs) {
        return new OrderLine(id, sku, sku, qty, Money.usd(price), Money.zero("USD"), Money.usd("0.00"), null, null, attrs);
    }

    static Order order(List<OrderLine> lines, boolean belowFloor) {
        Money merch = Money.zero("USD");
        for (OrderLine l : lines) merch = merch.add(l.extendedPrice());
        Totals totals = new Totals(merch, Money.zero("USD"), Money.zero("USD"), Money.zero("USD"), Money.zero("USD"), Money.zero("USD"), merch);
        MarginSnapshot margin = new MarginSnapshot(merch.amount(), BigDecimal.TEN, 0.2, 0.12, belowFloor, false, null, Instant.now());
        Address ship = new Address("ship_to", "J", "1 Market St", null, "SF", "CA", "94105", "US", null, "residential", true, null, null);
        return new Order("ord_1", "web", "W-1", "standard_ship", "c", null, lines, List.of(ship), List.of(), List.of(), List.of(), List.of(), null, null, totals, margin,
            "order.standard_ship", 1, "released", "ground", "USD", Map.of(), Instant.now(), Instant.now(), Instant.now(), 1);
    }

    static ModificationRules.LineFacts facts(String id, Map<String, Integer> qty) { return new ModificationRules.LineFacts(id, qty, CANCELLABLE); }

    static ModificationRules.Facts facts(Map<String, ModificationRules.LineFacts> lines, Map<String, Integer> atp, boolean intercept) {
        return new ModificationRules.Facts(lines, "released", atp, intercept, Money.usd("15.00"), Map.of("HOOD-GRY-M", Money.usd("68.00")), true);
    }

    @Test
    void cancelLineBeforeShipmentIsAllowedWithRefundAndPartialWhenSomeShipped() {
        Order o = order(List.of(line("L1", "TEE-BLK-M", 4, "28.00", Map.of())), false);
        Feasibility f = ModificationRules.check(o, new Modification.CancelLine("L1"), facts(Map.of("L1", facts("L1", Map.of("awaiting_sourcing", 4))), Map.of(), false));
        assertThat(f.allowed()).isTrue();
        assertThat(f.costDelta()).isEqualTo(Money.usd("-112.00"));
        assertThat(f.affectedLines()).containsExactly("L1");

        Feasibility partial = ModificationRules.check(o, new Modification.CancelLine("L1"), facts(Map.of("L1", facts("L1", Map.of("shipped", 1, "released", 3))), Map.of(), false));
        assertThat(partial.allowed()).isTrue();
        assertThat(partial.costDelta()).isEqualTo(Money.usd("-84.00"));
        assertThat(partial.reasons()).anyMatch(r -> r.startsWith("partial_cancel")).anyMatch(r -> r.startsWith("reservation_released"));

        Feasibility shipped = ModificationRules.check(o, new Modification.CancelLine("L1"), facts(Map.of("L1", facts("L1", Map.of("shipped", 4))), Map.of(), false));
        assertThat(shipped.allowed()).isFalse();
        assertThat(shipped.reasons().get(0)).startsWith("line_already_shipped");

        Feasibility picking = ModificationRules.check(o, new Modification.CancelLine("L1"), facts(Map.of("L1", facts("L1", Map.of("picking", 4))), Map.of(), false));
        assertThat(picking.allowed()).isFalse();
        assertThat(picking.reasons().get(0)).startsWith("line_not_cancellable_in_state");

        assertThat(ModificationRules.check(o, new Modification.CancelLine("L9"), facts(Map.of(), Map.of(), false)).reasons().get(0)).startsWith("unknown_line");
    }

    @Test
    void cancelOrderRefusedOncePartiallyShipped() {
        Order o = order(List.of(line("L1", "TEE-BLK-M", 1, "28.00", Map.of()), line("L2", "CAP-NVY", 1, "32.00", Map.of())), false);
        Feasibility ok = ModificationRules.check(o, new Modification.CancelOrder(), facts(Map.of("L1", facts("L1", Map.of("released", 1)), "L2", facts("L2", Map.of("awaiting_sourcing", 1))), Map.of(), false));
        assertThat(ok.allowed()).isTrue();
        assertThat(ok.costDelta()).isEqualTo(Money.usd("-60.00"));
        assertThat(ok.affectedLines()).containsExactly("L1", "L2");

        Feasibility no = ModificationRules.check(o, new Modification.CancelOrder(), facts(Map.of("L1", facts("L1", Map.of("shipped", 1)), "L2", facts("L2", Map.of("awaiting_sourcing", 1))), Map.of(), false));
        assertThat(no.allowed()).isFalse();
        assertThat(no.reasons().get(0)).startsWith("order_partially_shipped");

        ModificationRules.Facts terminal = new ModificationRules.Facts(Map.of(), "cancelled", Map.of(), false, Money.usd("0"), Map.of(), true);
        assertThat(ModificationRules.check(o, new Modification.CancelOrder(), terminal).reasons()).containsExactly("order_cancelled");
    }

    @Test
    void changeQtyDecreaseCancelsUnitsAndIncreaseNeedsAtp() {
        Order o = order(List.of(line("L1", "TEE-BLK-M", 3, "28.00", Map.of())), false);
        Feasibility down = ModificationRules.check(o, new Modification.ChangeQty("L1", 1), facts(Map.of("L1", facts("L1", Map.of("awaiting_sourcing", 3))), Map.of(), false));
        assertThat(down.allowed()).isTrue();
        assertThat(down.costDelta()).isEqualTo(Money.usd("-56.00"));

        Feasibility tooFar = ModificationRules.check(o, new Modification.ChangeQty("L1", 1), facts(Map.of("L1", facts("L1", Map.of("shipped", 2, "awaiting_sourcing", 1))), Map.of(), false));
        assertThat(tooFar.allowed()).isFalse();
        assertThat(tooFar.reasons().get(0)).startsWith("insufficient_cancellable_qty");

        Feasibility noAtp = ModificationRules.check(o, new Modification.ChangeQty("L1", 5), facts(Map.of("L1", facts("L1", Map.of("awaiting_sourcing", 3))), Map.of("TEE-BLK-M", 1), false));
        assertThat(noAtp.allowed()).isFalse();
        assertThat(noAtp.reasons().get(0)).startsWith("insufficient_atp");

        Feasibility up = ModificationRules.check(o, new Modification.ChangeQty("L1", 5), facts(Map.of("L1", facts("L1", Map.of("awaiting_sourcing", 3))), Map.of("TEE-BLK-M", 10), false));
        assertThat(up.allowed()).isTrue();
        assertThat(up.costDelta()).isEqualTo(Money.usd("56.00"));
        assertThat(up.requiresApproval()).isFalse();

        Order belowFloor = order(List.of(line("L1", "TEE-BLK-M", 3, "28.00", Map.of())), true);
        assertThat(ModificationRules.check(belowFloor, new Modification.ChangeQty("L1", 5), facts(Map.of("L1", facts("L1", Map.of("awaiting_sourcing", 3))), Map.of("TEE-BLK-M", 10), false)).requiresApproval()).isTrue();

        assertThat(ModificationRules.check(o, new Modification.ChangeQty("L1", 3), facts(Map.of("L1", facts("L1", Map.of("awaiting_sourcing", 3))), Map.of(), false)).reasons()).containsExactly("no_change");
        assertThat(ModificationRules.check(o, new Modification.ChangeQty("L1", 0), facts(Map.of("L1", facts("L1", Map.of("awaiting_sourcing", 3))), Map.of(), false)).reasons().get(0)).startsWith("qty_must_be_positive");
        assertThat(ModificationRules.check(o, new Modification.ChangeQty("L1", 5), facts(Map.of("L1", facts("L1", Map.of("shipped", 3))), Map.of("TEE-BLK-M", 10), false)).reasons().get(0)).startsWith("line_already_shipped");
    }

    @Test
    void changeAddressAfterShipmentNeedsCarrierInterceptWhenEnabled() {
        Order o = order(List.of(line("L1", "TEE-BLK-M", 1, "28.00", Map.of())), false);
        Address ny = new Address("ship_to", "J", "5 5th Ave", null, "New York", "NY", "10001", "US", null, null, false, null, null);
        Feasibility before = ModificationRules.check(o, new Modification.ChangeAddress(ny), facts(Map.of("L1", facts("L1", Map.of("released", 1))), Map.of(), false));
        assertThat(before.allowed()).isTrue();
        assertThat(before.carrierIntercept()).isFalse();
        assertThat(before.reasons()).anyMatch(r -> r.startsWith("lines_released_repromise")).anyMatch(r -> r.startsWith("tax_recalculated"));

        Feasibility disabled = ModificationRules.check(o, new Modification.ChangeAddress(ny), facts(Map.of("L1", facts("L1", Map.of("shipped", 1))), Map.of(), false));
        assertThat(disabled.allowed()).isFalse();
        assertThat(disabled.reasons().get(0)).startsWith("carrier_intercept_disabled");

        Feasibility intercept = ModificationRules.check(o, new Modification.ChangeAddress(ny), facts(Map.of("L1", facts("L1", Map.of("shipped", 1))), Map.of(), true));
        assertThat(intercept.allowed()).isTrue();
        assertThat(intercept.carrierIntercept()).isTrue();
        assertThat(intercept.costDelta()).isEqualTo(Money.usd("15.00"));

        Feasibility delivered = ModificationRules.check(o, new Modification.ChangeAddress(ny), facts(Map.of("L1", facts("L1", Map.of("delivered", 1))), Map.of(), true));
        assertThat(delivered.reasons()).containsExactly("order_delivered");

        ModificationRules.Facts bad = new ModificationRules.Facts(Map.of("L1", facts("L1", Map.of("released", 1))), "released", Map.of(), false, Money.usd("15"), Map.of(), false);
        assertThat(ModificationRules.check(o, new Modification.ChangeAddress(ny), bad).reasons()).containsExactly("address_undeliverable_or_invalid");
        assertThat(ModificationRules.check(o, new Modification.ChangeAddress(null), bad).reasons()).containsExactly("address_required");
    }

    @Test
    void changeShipMethodBlockedForHazmatAirAndAfterShipment() {
        Order hazmat = order(List.of(line("L1", "PERF-50ML", 1, "120.00", Map.of("hazmat", true))), false);
        Feasibility air = ModificationRules.check(hazmat, new Modification.ChangeShipMethod("air_2day"), facts(Map.of("L1", facts("L1", Map.of("awaiting_sourcing", 1))), Map.of(), false));
        assertThat(air.allowed()).isFalse();
        assertThat(air.reasons().get(0)).startsWith("HAZMAT_AIR");

        Order plain = order(List.of(line("L1", "TEE-BLK-M", 1, "28.00", Map.of())), false);
        Feasibility ok = ModificationRules.check(plain, new Modification.ChangeShipMethod("air_2day"), facts(Map.of("L1", facts("L1", Map.of("released", 1))), Map.of(), false));
        assertThat(ok.allowed()).isTrue();
        assertThat(ok.reasons()).anyMatch(r -> r.startsWith("lines_released_repromise"));
        assertThat(ModificationRules.check(plain, new Modification.ChangeShipMethod("ground"), facts(Map.of(), Map.of(), false)).reasons()).containsExactly("no_change");
        assertThat(ModificationRules.check(plain, new Modification.ChangeShipMethod("air_2day"), facts(Map.of("L1", facts("L1", Map.of("shipped", 1))), Map.of(), false)).reasons()).containsExactly("order_already_shipped");
    }

    @Test
    void changeItemOnlyBeforeReleaseWithKnownSkuAndAtp() {
        Order o = order(List.of(line("L1", "TEE-BLK-M", 2, "28.00", Map.of())), false);
        Feasibility ok = ModificationRules.check(o, new Modification.ChangeItem("L1", "HOOD-GRY-M"), facts(Map.of("L1", facts("L1", Map.of("awaiting_sourcing", 2))), Map.of("HOOD-GRY-M", 5), false));
        assertThat(ok.allowed()).isTrue();
        assertThat(ok.costDelta()).isEqualTo(Money.usd("80.00"));
        assertThat(ok.reasons().get(0)).startsWith("price_difference_charged");

        assertThat(ModificationRules.check(o, new Modification.ChangeItem("L1", "HOOD-GRY-M"), facts(Map.of("L1", facts("L1", Map.of("released", 2))), Map.of("HOOD-GRY-M", 5), false)).reasons().get(0)).startsWith("line_already_released");
        assertThat(ModificationRules.check(o, new Modification.ChangeItem("L1", "NOPE"), facts(Map.of("L1", facts("L1", Map.of("awaiting_sourcing", 2))), Map.of("NOPE", 5), false)).reasons().get(0)).startsWith("unknown_sku");
        assertThat(ModificationRules.check(o, new Modification.ChangeItem("L1", "HOOD-GRY-M"), facts(Map.of("L1", facts("L1", Map.of("awaiting_sourcing", 2))), Map.of("HOOD-GRY-M", 1), false)).reasons().get(0)).startsWith("insufficient_atp");
        assertThat(ModificationRules.check(o, new Modification.ChangeItem("L1", "TEE-BLK-M"), facts(Map.of("L1", facts("L1", Map.of("awaiting_sourcing", 2))), Map.of(), false)).reasons()).containsExactly("no_change");
    }
}
