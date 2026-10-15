package ai.intentoms.agent;

import ai.intentoms.finance.model.Money;
import ai.intentoms.order.model.Address;
import ai.intentoms.order.model.FulfilmentGroup;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.model.OrderLine;
import ai.intentoms.order.model.OrderSummaryView;
import ai.intentoms.order.model.OrderView;
import ai.intentoms.order.model.ReturnSummary;
import ai.intentoms.order.model.ShipmentSummary;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.returns.model.ReturnRecord;
import ai.intentoms.statemachine.model.EntityStatus;
import ai.intentoms.statemachine.model.QuantityBucket;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Customer-safe projections of orders, returns and ledger events for agents and UCP clients (FR-AGT-01/02, GR-15):
 * status, lines with quantity statuses and promise dates, shipments, returns and totals the buyer already knows. Margin,
 * cost, COGS, risk, fraud, decision internals, node economics and raw attributes are never included; nested maps that
 * reach the projection pass {@link #redact}, which drops any key matching {@link #INTERNAL}.
 */
public final class CustomerSafeOrderView {
    /** Keys that never reach a customer or agent. */
    public static final Pattern INTERNAL = Pattern.compile("(?i)(margin|cost|cogs|risk|fraud|abuse|pnl|penalt|labou?r|landed|velocity|score|markdown|explain|internal|secret|hash)");

    private CustomerSafeOrderView() {}

    public static Map<String, Object> order(OrderView v) {
        Order o = v.order();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", o.id());
        out.put("channel", o.channel());
        out.put("channelOrderId", o.channelOrderId());
        out.put("orderType", o.orderType());
        out.put("status", v.header() != null ? v.header().state() : o.status());
        out.put("customerRef", o.customerRef());
        out.put("placedAt", o.placedAt());
        out.put("shipMethod", o.shipMethod());
        out.put("currency", o.currency());
        o.shipTo().ifPresent(a -> out.put("shipTo", address(a)));
        Map<String, LocalDate> promiseByLine = new LinkedHashMap<>();
        Map<String, String> groupByLine = new LinkedHashMap<>();
        List<Map<String, Object>> fulfilment = new ArrayList<>();
        if (v.fulfilmentGroups() != null) for (FulfilmentGroup g : v.fulfilmentGroups()) {
            Map<String, Object> gm = new LinkedHashMap<>();
            gm.put("groupId", g.id());
            gm.put("status", g.status());
            gm.put("promiseDate", g.promiseDate());
            gm.put("shipMethod", g.shipMethod());
            gm.put("lineIds", g.allocations() == null ? List.of() : g.allocations().stream().map(FulfilmentGroup.Allocation::lineId).toList());
            fulfilment.add(gm);
            if (g.allocations() != null) for (FulfilmentGroup.Allocation a : g.allocations()) { promiseByLine.put(a.lineId(), g.promiseDate()); groupByLine.put(a.lineId(), g.id()); }
        }
        Map<String, EntityStatus> statusByLine = new LinkedHashMap<>();
        if (v.lineStatuses() != null) for (EntityStatus s : v.lineStatuses()) {
            String id = s.entityId();
            int i = id.indexOf(':');
            statusByLine.put(i > 0 ? id.substring(i + 1) : id, s);
        }
        Map<String, List<Map<String, Object>>> trackingByLine = new LinkedHashMap<>();
        List<Map<String, Object>> shipments = new ArrayList<>();
        if (v.shipments() != null) for (ShipmentSummary s : v.shipments()) {
            Map<String, Object> sm = shipment(s);
            shipments.add(sm);
            if (s.lines() != null) for (ShipmentSummary.ShipmentLine l : s.lines()) trackingByLine.computeIfAbsent(l.lineId(), k -> new ArrayList<>()).add(tracking(s));
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        for (OrderLine l : o.lines()) {
            Map<String, Object> lm = new LinkedHashMap<>();
            lm.put("lineId", l.lineId());
            lm.put("sku", l.sku());
            lm.put("description", l.description());
            lm.put("qty", l.qty());
            lm.put("unitPrice", money(l.unitPrice()));
            if (l.discount() != null && l.discount().isPositive()) lm.put("discount", money(l.discount()));
            if (l.tax() != null) lm.put("tax", money(l.tax()));
            EntityStatus st = statusByLine.get(l.lineId());
            lm.put("status", st != null ? st.state() : l.status());
            lm.put("quantities", st == null ? List.of() : st.quantities().stream().map(CustomerSafeOrderView::bucket).toList());
            lm.put("promiseDate", promiseByLine.get(l.lineId()));
            lm.put("fulfilmentGroupId", groupByLine.get(l.lineId()));
            lm.put("tracking", trackingByLine.getOrDefault(l.lineId(), List.of()));
            lines.add(lm);
        }
        out.put("lines", lines);
        if (o.totals() != null) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("merchandise", money(o.totals().merchandise()));
            t.put("discounts", money(o.totals().discounts()));
            t.put("shipping", money(o.totals().shipping()));
            t.put("tax", money(o.totals().tax()));
            t.put("duties", money(o.totals().duties()));
            t.put("fees", money(o.totals().fees()));
            t.put("grandTotal", money(o.totals().grandTotal()));
            out.put("totals", t);
        }
        out.put("fulfilment", fulfilment);
        out.put("shipments", shipments);
        out.put("returns", v.returns() == null ? List.of() : v.returns().stream().map(CustomerSafeOrderView::returnSummary).toList());
        out.put("onHold", v.holds() != null && !v.holds().isEmpty());
        if (o.intent() != null && o.intent().policy() != null) out.put("fulfilmentIntent", o.intent().policy());
        if (o.provenance() != null) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("agentId", o.provenance().agentId());
            p.put("protocol", o.provenance().protocol());
            p.put("mandateId", o.provenance().mandateId());
            p.put("buyerRef", o.provenance().buyerRef());
            out.put("agent", p);
        }
        return out;
    }

    public static Map<String, Object> summary(OrderSummaryView s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderId", s.id());
        m.put("channel", s.channel());
        m.put("channelOrderId", s.channelOrderId());
        m.put("orderType", s.orderType());
        m.put("customerRef", s.customerRef());
        m.put("status", s.status());
        m.put("total", money(s.total()));
        m.put("lineCount", s.lineCount());
        m.put("placedAt", s.placedAt());
        m.put("promiseDate", s.promiseDate());
        m.put("onHold", s.held());
        if (s.agentId() != null) m.put("agentId", s.agentId());
        return m;
    }

    /** Return as the buyer may see it: no risk score, reason-code probabilities or decision ids. */
    public static Map<String, Object> returnRecord(ReturnRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rma", r.rma());
        m.put("orderId", r.orderId());
        m.put("status", r.status());
        m.put("channel", r.channel());
        m.put("reason", r.reason());
        m.put("createdAt", r.createdAt());
        m.put("refundTiming", r.refundTiming());
        m.put("refundAmount", money(r.refundAmount()));
        m.put("merchandiseTotal", money(r.merchandiseTotal()));
        List<Map<String, Object>> lines = new ArrayList<>();
        if (r.lines() != null) for (ReturnRecord.Line l : r.lines()) {
            Map<String, Object> lm = new LinkedHashMap<>();
            lm.put("lineId", l.lineId());
            lm.put("sku", l.sku());
            lm.put("qty", l.qty());
            lm.put("unitPrice", money(l.unitPrice()));
            lm.put("reason", l.reason());
            lines.add(lm);
        }
        m.put("lines", lines);
        if (r.eligibility() != null) m.put("eligible", r.eligibility().eligible());
        if (r.refund() != null) m.put("refund", Map.of("status", String.valueOf(r.refund().status()), "amount", money(r.refund().amount()) == null ? Map.of() : money(r.refund().amount())));
        return m;
    }

    /** Ledger events with internal payload keys removed (GR-15). */
    public static List<Map<String, Object>> events(List<Event> events) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Event e : events) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id());
            m.put("type", e.type());
            m.put("at", e.occurredAt());
            m.put("sequence", e.sequence());
            m.put("data", redact(e.payload()));
            out.add(m);
        }
        return out;
    }

    /** Recursively removes keys matching {@link #INTERNAL}. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> redact(Map<String, Object> m) {
        if (m == null) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> {
            if (INTERNAL.matcher(k).find()) return;
            out.put(k, redactValue(v));
        });
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object redactValue(Object v) {
        if (v instanceof Map<?, ?> inner) return redact((Map<String, Object>) inner);
        if (v instanceof List<?> l) return l.stream().map(CustomerSafeOrderView::redactValue).toList();
        return v;
    }

    /** True when no key anywhere in the structure matches {@link #INTERNAL}; used by tests and as a release guard. */
    @SuppressWarnings("unchecked")
    public static boolean isCustomerSafe(Object node) {
        if (node instanceof Map<?, ?> m) {
            for (var e : m.entrySet()) {
                if (INTERNAL.matcher(String.valueOf(e.getKey())).find()) return false;
                if (!isCustomerSafe(e.getValue())) return false;
            }
            return true;
        }
        if (node instanceof List<?> l) { for (Object o : l) if (!isCustomerSafe(o)) return false; }
        return true;
    }

    public static Map<String, Object> money(Money m) {
        if (m == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("amount", m.amount());
        out.put("currency", m.currency());
        return out;
    }

    private static Map<String, Object> address(Address a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", a.name());
        m.put("line1", a.line1());
        m.put("line2", a.line2());
        m.put("city", a.city());
        m.put("state", a.state());
        m.put("zip", a.zip());
        m.put("country", a.country());
        return m;
    }

    private static Map<String, Object> bucket(QuantityBucket b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", b.state());
        m.put("qty", b.qty());
        m.put("since", b.since());
        return m;
    }

    private static Map<String, Object> shipment(ShipmentSummary s) {
        Map<String, Object> m = tracking(s);
        m.put("lines", s.lines() == null ? List.of() : s.lines().stream().map(l -> Map.of("lineId", l.lineId(), "qty", l.qty())).toList());
        return m;
    }

    private static Map<String, Object> tracking(ShipmentSummary s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("shipmentId", s.id());
        m.put("carrier", s.carrier());
        m.put("service", s.service());
        m.put("tracking", s.tracking());
        m.put("status", s.status());
        m.put("shippedAt", s.shippedAt());
        m.put("deliveredAt", s.deliveredAt());
        m.put("eta", s.eta());
        return m;
    }

    private static Map<String, Object> returnSummary(ReturnSummary r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rma", r.rma());
        m.put("status", r.status());
        m.put("createdAt", r.createdAt());
        m.put("channel", r.channel());
        m.put("lineCount", r.lineCount());
        m.put("refundAmount", money(r.refundAmount()));
        m.put("refundTiming", r.refundTiming());
        return m;
    }
}
