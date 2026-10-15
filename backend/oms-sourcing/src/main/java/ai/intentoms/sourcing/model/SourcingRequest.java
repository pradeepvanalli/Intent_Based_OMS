package ai.intentoms.sourcing.model;

import ai.intentoms.finance.model.Money;
import ai.intentoms.order.model.Charge;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.model.OrderLine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the candidate generator and plan builder work from (FR-SRC-01). Built from an {@link Order} by
 * {@link #from(Order, Constraints)}: digital lines need no node and are left out.
 *
 * @param shippingRevenue shipping charged to the buyer (revenue side of the P&L, FR-FIN-04)
 */
public record SourcingRequest(String orderId, List<RequestLine> lines, Destination destination, String shipMethod, String orderType,
                              Constraints constraints, Money shippingRevenue) {
    public static final String METHOD_PICKUP = "pickup";
    public static final String METHOD_SAME_DAY = "same_day";
    public static final String METHOD_GROUND = "ground";

    public SourcingRequest {
        lines = lines == null ? List.of() : List.copyOf(lines);
        shipMethod = shipMethod == null || shipMethod.isBlank() ? METHOD_GROUND : shipMethod;
        constraints = constraints == null ? Constraints.none() : constraints;
        shippingRevenue = shippingRevenue == null ? Money.zero(currencyOf(lines)) : shippingRevenue;
    }

    /** One line to source. Attributes carry catalogue facts: hazmat, weight_kg, cogs, hs_code, urgent, markdown_risk. */
    public record RequestLine(String lineId, String sku, int qty, Money unitPrice, Money discount, Map<String, Object> attributes) {
        public RequestLine {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
            unitPrice = unitPrice == null ? Money.zero("USD") : unitPrice;
            discount = discount == null ? Money.zero(unitPrice.currency()) : discount;
        }
        public boolean hazmat() { return flag("hazmat"); }
        public boolean urgent() { return flag("urgent"); }
        public double weightKg() { Object w = attributes.get("weight_kg"); return w instanceof Number n ? n.doubleValue() : 0.5; }
        public Money cogs() { Object c = attributes.get("cogs"); return c instanceof Number n ? Money.of(n.doubleValue(), unitPrice.currency()) : Money.zero(unitPrice.currency()); }
        public String hsCode() { Object h = attributes.get("hs_code"); return h == null ? "" : h.toString(); }
        /** Fraction of the line value at risk of markdown if it ships late / from the wrong node; 0 when absent (FR-SRC-02). */
        public double markdownRisk() { Object m = attributes.get("markdown_risk"); return m instanceof Number n ? Math.max(0, Math.min(1, n.doubleValue())) : 0.0; }
        public Money extendedPrice() { return unitPrice.multiply(qty).subtract(discount); }
        public Money extendedPrice(int units) { return qty == 0 ? Money.zero(unitPrice.currency()) : unitPrice.multiply(units).subtract(discount.multiply(units).multiply(java.math.BigDecimal.ONE.divide(java.math.BigDecimal.valueOf(qty), 6, java.math.RoundingMode.HALF_EVEN))); }
        private boolean flag(String k) { Object v = attributes.get(k); return v instanceof Boolean b ? b : v != null && "true".equalsIgnoreCase(v.toString()); }
    }

    /** Ship-to (or pickup) destination. Country defaults to US. */
    public record Destination(String zip, String state, String country) {
        public Destination {
            country = country == null || country.isBlank() ? "US" : country.toUpperCase();
            state = state == null ? null : state.toUpperCase();
        }
    }

    /**
     * Order-type constraints (order-types.yaml {@code sourcing:} block).
     *
     * @param nodeTypes      restrict to these node types (empty = any)
     * @param capabilities   extra capabilities every node must have
     * @param maxDistanceKm  exclude nodes farther than this (null = no limit)
     * @param allowBackorder unsourced quantity becomes a backorder instead of failing the plan
     * @param excludedNodes  nodes excluded for this run (re-sourcing after a reject, FR-SRC-07)
     */
    public record Constraints(Set<String> nodeTypes, Set<String> capabilities, Double maxDistanceKm, boolean allowBackorder, Set<String> excludedNodes) {
        public Constraints {
            nodeTypes = nodeTypes == null ? Set.of() : Set.copyOf(nodeTypes);
            capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
            excludedNodes = excludedNodes == null ? Set.of() : Set.copyOf(excludedNodes);
        }
        public static Constraints none() { return new Constraints(Set.of(), Set.of(), null, false, Set.of()); }
        public Constraints excluding(Set<String> nodes) {
            Set<String> all = new java.util.HashSet<>(excludedNodes);
            all.addAll(nodes);
            return new Constraints(nodeTypes, capabilities, maxDistanceKm, allowBackorder, all);
        }

        /** Parses the {@code sourcing:} map of an order type or {@code order.extensions.sourcing}. */
        @SuppressWarnings("unchecked")
        public static Constraints fromMap(Map<String, Object> m) {
            if (m == null || m.isEmpty()) return none();
            Set<String> types = m.get("nodeTypes") instanceof List<?> l ? Set.copyOf(l.stream().map(String::valueOf).toList()) : Set.of();
            Set<String> caps = m.get("capabilities") instanceof List<?> l ? Set.copyOf(l.stream().map(String::valueOf).toList()) : Set.of();
            Double maxKm = m.get("maxDistanceKm") instanceof Number n ? n.doubleValue() : null;
            boolean backorder = m.get("allowBackorder") instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(m.getOrDefault("allowBackorder", "false")));
            Set<String> excluded = m.get("excludedNodes") instanceof List<?> l ? Set.copyOf(l.stream().map(String::valueOf).toList()) : Set.of();
            return new Constraints(types, caps, maxKm, backorder, excluded);
        }
    }

    public boolean isPickup() { return METHOD_PICKUP.equalsIgnoreCase(shipMethod); }
    public boolean hasHazmat() { return lines.stream().anyMatch(RequestLine::hazmat); }
    public String currency() { return currencyOf(lines); }
    public int totalUnits() { int n = 0; for (RequestLine l : lines) n += l.qty(); return n; }

    /** Requested units per sku (a sku may appear on several lines). */
    public Map<String, Integer> unitsBySku() {
        Map<String, Integer> m = new java.util.TreeMap<>();
        for (RequestLine l : lines) m.merge(l.sku(), l.qty(), Integer::sum);
        return m;
    }

    public SourcingRequest withConstraints(Constraints c) { return new SourcingRequest(orderId, lines, destination, shipMethod, orderType, c, shippingRevenue); }

    private static String currencyOf(List<RequestLine> lines) { return lines == null || lines.isEmpty() ? "USD" : lines.get(0).unitPrice().currency(); }

    /** Builds the request from a canonical order; pickup orders use the {@code pickup} address when present. */
    public static SourcingRequest from(Order order, Constraints constraints) {
        List<RequestLine> lines = new ArrayList<>();
        for (OrderLine l : order.lines()) {
            if (l.digital()) continue;
            if ("cancelled".equalsIgnoreCase(l.status())) continue;
            lines.add(new RequestLine(l.lineId(), l.sku(), l.qty(), l.unitPrice(), l.discount(), l.attributes()));
        }
        String method = order.shipMethod() == null || order.shipMethod().isBlank() ? METHOD_GROUND : order.shipMethod();
        if ("bopis".equalsIgnoreCase(order.orderType())) method = METHOD_PICKUP;
        var addr = (METHOD_PICKUP.equalsIgnoreCase(method) ? order.address("pickup") : java.util.Optional.<ai.intentoms.order.model.Address>empty()).or(order::shipTo).orElse(null);
        Destination dest = addr == null ? new Destination(null, null, "US") : new Destination(addr.zip(), addr.state(), addr.country());
        Money shippingRevenue = order.totals() != null && order.totals().shipping() != null ? order.totals().shipping() : Money.zero(order.currency() == null ? "USD" : order.currency());
        if (shippingRevenue.isZero() && order.charges() != null) {
            for (Charge c : order.charges()) if ("shipping".equalsIgnoreCase(c.type())) shippingRevenue = shippingRevenue.add(c.amount());
        }
        return new SourcingRequest(order.id(), lines, dest, method, order.orderType(), constraints, shippingRevenue);
    }
}
