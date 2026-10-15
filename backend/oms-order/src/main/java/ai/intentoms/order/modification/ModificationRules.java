package ai.intentoms.order.modification;

import ai.intentoms.finance.model.Money;
import ai.intentoms.order.model.Address;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.model.OrderLine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure feasibility rules for order modifications (FR-CS-02): evaluated against the current quantity-level statuses of
 * each line (from the state machine) rather than a header flag, so a line that is 4 shipped / 6 released can still have
 * six units cancelled. No I/O; the service supplies ATP, catalogue prices and tenant policy through {@link Facts}.
 */
public final class ModificationRules {
    /** States from which the line model allows {@code api.cancel_line} are supplied per line; these are the "already left the building" states. */
    public static final Set<String> SHIPPED_STATES = Set.of("shipped", "delivered", "picked_up", "in_transit");
    public static final Set<String> DELIVERED_STATES = Set.of("delivered", "picked_up");
    /** States meaning a node is working the line: cancelling or changing it needs a re-release. */
    public static final Set<String> AT_NODE_STATES = Set.of("released", "picking", "packed", "picked", "ready_for_pickup", "staged");
    public static final Set<String> PRE_RELEASE_STATES = Set.of("created", "awaiting_sourcing", "backordered");

    /** Status facts for one line: quantity per state and the states the model lets {@code api.cancel_line} drain right now. */
    public record LineFacts(String lineId, Map<String, Integer> qtyByState, Set<String> cancellableStates) {
        public LineFacts {
            qtyByState = qtyByState == null ? Map.of() : Map.copyOf(qtyByState);
            cancellableStates = cancellableStates == null ? Set.of() : Set.copyOf(cancellableStates);
        }
        public int total() { return qtyByState.values().stream().mapToInt(Integer::intValue).sum(); }
        public int in(Set<String> states) { int n = 0; for (var e : qtyByState.entrySet()) if (states.contains(e.getKey())) n += e.getValue(); return n; }
        public int cancellable() { return in(cancellableStates); }
        public int shipped() { return in(SHIPPED_STATES); }
        public int delivered() { return in(DELIVERED_STATES); }
        public int atNode() { return in(AT_NODE_STATES); }
        public int active() { return total() - qtyByState.getOrDefault("cancelled", 0); }
    }

    /** Tenant policy and external facts the rules need. */
    public record Facts(Map<String, LineFacts> lines, String headerState, Map<String, Integer> networkAtp, boolean carrierInterceptEnabled, Money carrierInterceptFee,
                        Map<String, Money> catalogPrices, boolean newAddressDeliverable) {
        public Facts {
            lines = lines == null ? Map.of() : Map.copyOf(lines);
            networkAtp = networkAtp == null ? Map.of() : Map.copyOf(networkAtp);
            catalogPrices = catalogPrices == null ? Map.of() : Map.copyOf(catalogPrices);
        }
        LineFacts line(String id) { return lines.get(id); }
        int atp(String sku) { return sku == null ? 0 : networkAtp.getOrDefault(sku, 0); }
        boolean anyShipped() { return lines.values().stream().anyMatch(l -> l.shipped() > 0); }
        boolean allDelivered() { return !lines.isEmpty() && lines.values().stream().allMatch(l -> l.active() == 0 || l.delivered() == l.active()); }
        boolean anyAtNode() { return lines.values().stream().anyMatch(l -> l.atNode() > 0); }
    }

    private ModificationRules() {}

    public static Feasibility check(Order order, Modification m, Facts facts) {
        return switch (m) {
            case Modification.CancelLine c -> cancelLine(order, c.lineId(), facts);
            case Modification.CancelOrder c -> cancelOrder(order, facts);
            case Modification.ChangeQty c -> changeQty(order, c, facts);
            case Modification.ChangeAddress c -> changeAddress(order, c.address(), facts);
            case Modification.ChangeShipMethod c -> changeShipMethod(order, c.shipMethod(), facts);
            case Modification.ChangeItem c -> changeItem(order, c, facts);
        };
    }

    static Feasibility cancelLine(Order order, String lineId, Facts facts) {
        OrderLine line = order.line(lineId).orElse(null);
        LineFacts lf = facts.line(lineId);
        if (line == null || lf == null) return Feasibility.denied("unknown_line: " + lineId, List.of());
        if ("cancelled".equals(line.status()) || lf.active() == 0) return Feasibility.denied("line_already_cancelled", List.of(lineId));
        int cancellable = lf.cancellable();
        if (cancellable == 0) {
            String why = lf.shipped() > 0 ? "line_already_shipped: " + lf.shipped() + " of " + lf.active() + " units shipped; create a return instead"
                : "line_not_cancellable_in_state: " + lf.qtyByState();
            return Feasibility.denied(why, List.of(lineId));
        }
        List<String> reasons = new ArrayList<>();
        if (cancellable < lf.active()) reasons.add("partial_cancel: " + cancellable + " of " + lf.active() + " units can be cancelled (" + (lf.active() - cancellable) + " shipped or being picked)");
        if (lf.atNode() > 0) reasons.add("reservation_released: units released to a node will be recalled and restocked");
        return new Feasibility(true, reasons, lineRefund(line, cancellable).negate(), false, null, null, List.of(lineId));
    }

    static Feasibility cancelOrder(Order order, Facts facts) {
        if (facts.headerState() != null && Set.of("cancelled", "delivered", "refunded").contains(facts.headerState())) return Feasibility.denied("order_" + facts.headerState(), List.of());
        List<String> affected = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        Money refund = Money.zero(order.currency());
        int cancellable = 0;
        for (OrderLine l : order.lines()) {
            if ("cancelled".equals(l.status())) continue;
            LineFacts lf = facts.line(l.lineId());
            if (lf == null || lf.active() == 0) continue;
            if (lf.shipped() > 0) return Feasibility.denied("order_partially_shipped: line " + l.lineId() + " has " + lf.shipped() + " units shipped; cancel remaining lines individually or create a return", List.of(l.lineId()));
            int c = lf.cancellable();
            if (c < lf.active()) return Feasibility.denied("line_in_process: line " + l.lineId() + " is being picked or packed; retry after it ships or ask the node to stop", List.of(l.lineId()));
            cancellable += c;
            affected.add(l.lineId());
            refund = refund.add(lineRefund(l, c));
            if (lf.atNode() > 0) reasons.add("reservation_released: line " + l.lineId() + " will be recalled from its node");
        }
        if (cancellable == 0) return Feasibility.denied("nothing_to_cancel", affected);
        Money total = order.totals() == null || order.totals().grandTotal() == null ? refund : order.totals().grandTotal();
        return new Feasibility(true, reasons, total.negate(), false, null, null, affected);
    }

    static Feasibility changeQty(Order order, Modification.ChangeQty c, Facts facts) {
        OrderLine line = order.line(c.lineId()).orElse(null);
        LineFacts lf = facts.line(c.lineId());
        if (line == null || lf == null) return Feasibility.denied("unknown_line: " + c.lineId(), List.of());
        if (c.qty() <= 0) return Feasibility.denied("qty_must_be_positive: use CancelLine to remove the line", List.of(c.lineId()));
        int current = line.qty();
        if (c.qty() == current) return Feasibility.denied("no_change", List.of(c.lineId()));
        if (c.qty() < current) {
            int decrease = current - c.qty();
            int cancellable = lf.cancellable();
            if (cancellable < decrease) return Feasibility.denied("insufficient_cancellable_qty: only " + cancellable + " of " + decrease + " units can still be cancelled", List.of(c.lineId()));
            List<String> reasons = new ArrayList<>();
            if (lf.atNode() > 0) reasons.add("reservation_released: released units will be reduced at the node");
            return new Feasibility(true, reasons, lineRefund(line, decrease).negate(), false, null, null, List.of(c.lineId()));
        }
        int increase = c.qty() - current;
        if (lf.shipped() > 0) return Feasibility.denied("line_already_shipped: add a new order for extra units", List.of(c.lineId()));
        int atp = facts.atp(line.sku());
        if (atp < increase) return Feasibility.denied("insufficient_atp: " + atp + " available across the network, " + increase + " requested", List.of(c.lineId()));
        List<String> reasons = new ArrayList<>();
        reasons.add("additional_units_sourced: " + increase + " units enter sourcing as a supplementary line");
        if (lf.atNode() > 0) reasons.add("existing units stay with their node; the promise for the new units may differ");
        Money delta = line.unitPrice().multiply(increase).add(lineTax(line, increase));
        return new Feasibility(true, reasons, delta, needsApproval(order), null, null, List.of(c.lineId()));
    }

    static Feasibility changeAddress(Order order, Address address, Facts facts) {
        if (address == null) return Feasibility.denied("address_required", List.of());
        if (facts.allDelivered()) return Feasibility.denied("order_delivered", List.of());
        if (!facts.newAddressDeliverable()) return Feasibility.denied("address_undeliverable_or_invalid", List.of());
        List<String> affected = order.lines().stream().filter(l -> !"cancelled".equals(l.status())).map(OrderLine::lineId).toList();
        List<String> reasons = new ArrayList<>();
        if (facts.anyShipped()) {
            if (!facts.carrierInterceptEnabled()) return Feasibility.denied("carrier_intercept_disabled: packages already shipped and the tenant does not allow carrier intercepts", affected);
            reasons.add("carrier_intercept_required: shipped packages will be redirected by the carrier (fee " + facts.carrierInterceptFee() + "); success is not guaranteed");
            return new Feasibility(true, reasons, facts.carrierInterceptFee(), false, true, null, affected);
        }
        if (facts.anyAtNode()) reasons.add("lines_released_repromise: released lines are re-sourced for the new destination; the promise may change");
        Address old = order.shipTo().orElse(null);
        if (old != null && address.state() != null && !address.state().equalsIgnoreCase(old.state())) reasons.add("tax_recalculated: destination state changed from " + old.state() + " to " + address.state());
        return new Feasibility(true, reasons, Money.zero(order.currency()), false, false, null, affected);
    }

    static Feasibility changeShipMethod(Order order, String method, Facts facts) {
        if (method == null || method.isBlank()) return Feasibility.denied("ship_method_required", List.of());
        if (method.equalsIgnoreCase(order.shipMethod())) return Feasibility.denied("no_change", List.of());
        if (facts.anyShipped()) return Feasibility.denied("order_already_shipped", List.of());
        List<String> affected = order.lines().stream().filter(l -> !"cancelled".equals(l.status())).map(OrderLine::lineId).toList();
        List<String> reasons = new ArrayList<>();
        if (order.hasHazmat() && isAir(method)) return Feasibility.denied("HAZMAT_AIR: hazardous items cannot ship via " + method, affected);
        if (facts.anyAtNode()) reasons.add("lines_released_repromise: released lines are re-released with the new service");
        reasons.add("shipping_repriced: carrier cost is re-quoted by sourcing");
        return new Feasibility(true, reasons, Money.zero(order.currency()), needsApproval(order), null, null, affected);
    }

    static Feasibility changeItem(Order order, Modification.ChangeItem c, Facts facts) {
        OrderLine line = order.line(c.lineId()).orElse(null);
        LineFacts lf = facts.line(c.lineId());
        if (line == null || lf == null) return Feasibility.denied("unknown_line: " + c.lineId(), List.of());
        if (c.newSku() == null || c.newSku().isBlank()) return Feasibility.denied("new_sku_required", List.of(c.lineId()));
        if (c.newSku().equals(line.sku())) return Feasibility.denied("no_change", List.of(c.lineId()));
        if (lf.active() == 0) return Feasibility.denied("line_already_cancelled", List.of(c.lineId()));
        if (lf.in(PRE_RELEASE_STATES) < lf.active()) return Feasibility.denied("line_already_released: cancel the line and order the new item instead", List.of(c.lineId()));
        Money newPrice = facts.catalogPrices().get(c.newSku());
        if (newPrice == null) return Feasibility.denied("unknown_sku: " + c.newSku(), List.of(c.lineId()));
        int atp = facts.atp(c.newSku());
        if (atp < line.qty()) return Feasibility.denied("insufficient_atp: " + atp + " available for " + c.newSku() + ", " + line.qty() + " needed", List.of(c.lineId()));
        Money delta = newPrice.subtract(line.unitPrice()).multiply(line.qty());
        List<String> reasons = new ArrayList<>();
        reasons.add(delta.isNegative() ? "price_difference_refunded: " + delta.abs() : delta.isZero() ? "even_exchange" : "price_difference_charged: " + delta);
        return new Feasibility(true, reasons, delta, needsApproval(order) && delta.isNegative(), null, null, List.of(c.lineId()));
    }

    static boolean needsApproval(Order order) { return order.margin() != null && order.margin().belowFloor() && !order.margin().approvalPresent(); }

    static boolean isAir(String method) {
        String m = method.toLowerCase(Locale.ROOT);
        return m.contains("air") || m.contains("overnight") || m.contains("express") || m.contains("next_day");
    }

    /** Refund for {@code qty} units of a line: price − proportional discount + proportional tax. */
    static Money lineRefund(OrderLine line, int qty) {
        Money gross = line.unitPrice().multiply(qty);
        Money discount = line.discount() == null ? Money.zero(line.unitPrice().currency()) : proportion(line.discount(), qty, line.qty());
        return gross.subtract(discount).add(lineTax(line, qty));
    }

    static Money lineTax(OrderLine line, int qty) { return line.tax() == null ? Money.zero(line.unitPrice().currency()) : proportion(line.tax(), qty, line.qty()); }

    private static Money proportion(Money m, int part, int whole) {
        if (whole <= 0) return Money.zero(m.currency());
        return m.multiply(BigDecimal.valueOf(part).divide(BigDecimal.valueOf(whole), 6, RoundingMode.HALF_EVEN));
    }

    /** Convenience for callers building facts from state-machine buckets. */
    public static Map<String, Integer> buckets(Map<String, Integer> qtyByState) { return qtyByState == null ? Map.of() : new LinkedHashMap<>(qtyByState); }
}
