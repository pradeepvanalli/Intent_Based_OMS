package ai.intentoms.orchestration.integration;

import ai.intentoms.inventory.model.InventoryPosition;
import ai.intentoms.platform.error.ValidationException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure mapping of inbound webhook bodies onto workflow signals and inventory positions (FR-INV-04, FR-WF-05). No Spring, so
 * the shapes are unit-tested in isolation.
 *
 * <p>WMS body: {@code {eventId, type shipped|short_pick|store_rejected|pick_started|packed|received|inspected, orderId,
 * fulfilmentGroupId?, lines?[{lineId, qty}], carrier?, service?, tracking?, rmaId?, reason?, condition?, note?}}.
 * Carrier body: {@code {eventId, type in_transit|out_for_delivery|delivered|exception|returned_to_sender|return_scan,
 * orderId?, tracking, rmaId?, shipmentId?}}.
 */
public final class WebhookPayloads {
    private WebhookPayloads() {}

    public static final Set<String> WMS_ORDER_TYPES = Set.of("shipped", "short_pick", "store_rejected", "pick_started", "packed", "picked", "staged_for_pickup", "picked_up");
    public static final Set<String> WMS_RETURN_TYPES = Set.of("received", "inspected");
    public static final Set<String> CARRIER_TYPES = Set.of("in_transit", "out_for_delivery", "delivered", "exception", "returned_to_sender", "return_scan");
    /** Carrier scan types that wake the order workflow, mapped to the signal name. */
    public static final Map<String, String> CARRIER_SIGNALS = Map.of("delivered", "delivered", "exception", "carrier_exception", "returned_to_sender", "returned_to_sender");

    /** Normalised WMS event. */
    public record WmsEvent(String eventId, String type, String orderId, String nodeId, String fulfilmentGroupId, List<Map<String, Object>> lines, String carrier, String service,
                           String tracking, String rmaId, String reason, String condition, String note) {
        public boolean forReturn() { return WMS_RETURN_TYPES.contains(type); }

        /** Workflow signal payload (JSON-friendly). */
        public Map<String, Object> signalPayload() {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("eventId", eventId);
            p.put("type", type);
            p.put("nodeId", nodeId);
            if (fulfilmentGroupId != null) p.put("fulfilmentGroupId", fulfilmentGroupId);
            if (!lines.isEmpty()) p.put("lines", lines);
            if (carrier != null) p.put("carrier", carrier);
            if (service != null) p.put("service", service);
            if (tracking != null) p.put("tracking", tracking);
            if (reason != null) p.put("reason", reason);
            return p;
        }
    }

    /** Normalised carrier event. */
    public record CarrierEvent(String eventId, String type, String orderId, String tracking, String rmaId, String shipmentId) {
        public String signal() { return CARRIER_SIGNALS.get(type); }

        public Map<String, Object> signalPayload() {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("eventId", eventId);
            p.put("type", type);
            if (tracking != null) p.put("tracking", tracking);
            if (shipmentId != null) p.put("shipmentId", shipmentId);
            return p;
        }
    }

    public static WmsEvent wms(String nodeId, Map<String, Object> body) {
        String type = required(body, "type").toLowerCase();
        if (!WMS_ORDER_TYPES.contains(type) && !WMS_RETURN_TYPES.contains(type)) throw new ValidationException("Unknown WMS event type '" + type + "'", Map.of("allowed", union(WMS_ORDER_TYPES, WMS_RETURN_TYPES)));
        String rmaId = str(body.get("rmaId"));
        String orderId = str(body.get("orderId"));
        if (WMS_RETURN_TYPES.contains(type) && (rmaId == null || rmaId.isBlank())) throw new ValidationException("rmaId is required for WMS event " + type);
        if (!WMS_RETURN_TYPES.contains(type) && (orderId == null || orderId.isBlank())) throw new ValidationException("orderId is required for WMS event " + type);
        return new WmsEvent(eventId(body, "wms"), type, orderId, nodeId, str(body.get("fulfilmentGroupId")), lines(body.get("lines")), str(body.get("carrier")), str(body.get("service")),
            str(body.get("tracking")), rmaId, str(body.get("reason")), str(body.get("condition")), str(body.get("note")));
    }

    public static CarrierEvent carrier(Map<String, Object> body) {
        String type = required(body, "type").toLowerCase();
        if (!CARRIER_TYPES.contains(type)) throw new ValidationException("Unknown carrier event type '" + type + "'", Map.of("allowed", CARRIER_TYPES));
        String rmaId = str(body.get("rmaId"));
        String tracking = str(body.get("tracking"));
        String orderId = str(body.get("orderId"));
        if ("return_scan".equals(type) && (rmaId == null || rmaId.isBlank())) throw new ValidationException("rmaId is required for carrier event return_scan");
        if (!"return_scan".equals(type) && (tracking == null || tracking.isBlank()) && (orderId == null || orderId.isBlank())) throw new ValidationException("tracking or orderId is required");
        return new CarrierEvent(eventId(body, "carrier"), type, orderId, tracking, rmaId, str(body.get("shipmentId")));
    }

    /** Inventory feed: {@code {eventId?, source?, asOf?, positions:[{sku, qty, nodeId?, supplyType?, demandType?, confidence?, asOf?}]}} or a bare list. */
    public static List<InventoryPosition> positions(String nodeId, Object body, Instant now) {
        List<?> raw;
        if (body instanceof List<?> l) raw = l;
        else if (body instanceof Map<?, ?> m && m.get("positions") instanceof List<?> l) raw = l;
        else throw new ValidationException("Body must be {positions:[...]} or a list of positions");
        Instant feedAsOf = body instanceof Map<?, ?> m && m.get("asOf") != null ? Instant.parse(String.valueOf(m.get("asOf"))) : now;
        List<InventoryPosition> out = new ArrayList<>();
        for (Object o : raw) {
            if (!(o instanceof Map<?, ?> p)) continue;
            String sku = str(p.get("sku"));
            if (sku == null) throw new ValidationException("position.sku is required");
            String node = p.get("nodeId") == null ? nodeId : String.valueOf(p.get("nodeId"));
            Object q = p.get("qty");
            int qty = q instanceof Number n ? n.intValue() : q == null ? 0 : Integer.parseInt(String.valueOf(q));
            double confidence = p.get("confidence") instanceof Number n ? n.doubleValue() : 0.0;
            Instant asOf = p.get("asOf") == null ? feedAsOf : Instant.parse(String.valueOf(p.get("asOf")));
            out.add(new InventoryPosition(sku, node, str(p.get("supplyType")), str(p.get("demandType")), qty, confidence, asOf).normalised(now));
        }
        if (out.isEmpty()) throw new ValidationException("At least one position is required");
        return out;
    }

    static List<Map<String, Object>> lines(Object o) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(o instanceof List<?> l)) return out;
        for (Object e : l) {
            if (!(e instanceof Map<?, ?> m) || m.get("lineId") == null) continue;
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("lineId", String.valueOf(m.get("lineId")));
            Object qty = m.get("qty");
            line.put("qty", qty instanceof Number n ? n.intValue() : qty == null ? 0 : Integer.parseInt(String.valueOf(qty)));
            if (m.get("sku") != null) line.put("sku", String.valueOf(m.get("sku")));
            out.add(line);
        }
        return out;
    }

    static String eventId(Map<String, Object> body, String source) {
        String id = str(body.get("eventId"));
        return id == null || id.isBlank() ? source + ":" + ai.intentoms.platform.ids.Ids.id() : id.trim();
    }

    private static String required(Map<String, Object> body, String key) {
        String v = str(body.get(key));
        if (v == null || v.isBlank()) throw new ValidationException(key + " is required");
        return v.trim();
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static Set<String> union(Set<String> a, Set<String> b) { Set<String> s = new java.util.TreeSet<>(a); s.addAll(b); return s; }
}
