package ai.intentoms.agent.ucp;

import ai.intentoms.agent.CustomerSafeOrderView;
import ai.intentoms.order.model.OrderView;
import ai.intentoms.platform.ledger.Event;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * UCP order capability payloads (FR-AGT-02): the customer-safe projection re-keyed in snake_case with per-line
 * {@code fulfillment}, order-level {@code tracking} and {@code returns}.
 */
public final class UcpViews {
    private UcpViews() {}

    @SuppressWarnings("unchecked")
    public static Map<String, Object> order(String version, OrderView v) {
        Map<String, Object> safe = CustomerSafeOrderView.order(v);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ucp_version", version);
        out.put("order_id", safe.get("orderId"));
        out.put("client_order_id", safe.get("channelOrderId"));
        out.put("status", safe.get("status"));
        out.put("placed_at", safe.get("placedAt"));
        out.put("currency", safe.get("currency"));
        out.put("ship_method", safe.get("shipMethod"));
        out.put("buyer_ref", safe.get("customerRef"));
        out.put("ship_to", snake((Map<String, Object>) safe.get("shipTo")));
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> l : (List<Map<String, Object>>) safe.get("lines")) {
            Map<String, Object> li = new LinkedHashMap<>();
            li.put("line_id", l.get("lineId"));
            li.put("sku", l.get("sku"));
            li.put("description", l.get("description"));
            li.put("quantity", l.get("qty"));
            li.put("unit_price", l.get("unitPrice"));
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("status", l.get("status"));
            f.put("quantities", ((List<Map<String, Object>>) l.get("quantities")).stream().map(b -> Map.of("state", b.get("state"), "quantity", b.get("qty"))).toList());
            f.put("promise_date", l.get("promiseDate"));
            f.put("tracking", ((List<Map<String, Object>>) l.get("tracking")).stream().map(UcpViews::snake).toList());
            li.put("fulfillment", f);
            items.add(li);
        }
        out.put("line_items", items);
        out.put("totals", snake((Map<String, Object>) safe.get("totals")));
        out.put("fulfillment", Map.of("status", safe.get("status"), "groups", ((List<Map<String, Object>>) safe.get("fulfilment")).stream().map(UcpViews::snake).toList()));
        out.put("tracking", ((List<Map<String, Object>>) safe.get("shipments")).stream().map(UcpViews::snake).toList());
        out.put("returns", ((List<Map<String, Object>>) safe.get("returns")).stream().map(UcpViews::snake).toList());
        out.put("on_hold", safe.get("onHold"));
        if (safe.get("agent") != null) out.put("agent", snake((Map<String, Object>) safe.get("agent")));
        return out;
    }

    public static Map<String, Object> events(String version, String orderId, List<Event> events) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ucp_version", version);
        out.put("order_id", orderId);
        out.put("events", CustomerSafeOrderView.events(events).stream().map(UcpViews::snake).toList());
        return out;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> snake(Map<String, Object> m) {
        if (m == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(toSnake(k), v instanceof Map<?, ?> inner ? snake((Map<String, Object>) inner)
            : v instanceof List<?> l ? l.stream().map(e -> e instanceof Map<?, ?> im ? snake((Map<String, Object>) im) : e).toList() : v));
        return out;
    }

    static String toSnake(String k) {
        StringBuilder sb = new StringBuilder();
        for (char c : k.toCharArray()) { if (Character.isUpperCase(c)) sb.append('_').append(Character.toLowerCase(c)); else sb.append(c); }
        return sb.toString();
    }
}
