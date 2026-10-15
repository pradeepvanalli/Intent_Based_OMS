package ai.intentoms.integrations.carrier;

import ai.intentoms.platform.error.ValidationException;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Pure mapping of an EasyPost event webhook ({@code tracker.updated} / {@code tracker.created}) onto the OMS carrier
 * webhook body {@code {eventId, type, tracking, shipmentId?}} consumed by {@code InboundEventService.carrier} (UC-05, UC-08):
 * {@code status} {@code in_transit|pre_transit} → in_transit, {@code out_for_delivery} → out_for_delivery, {@code delivered}
 * → delivered, {@code failure|error|cancelled} → exception, {@code return_to_sender} → returned_to_sender,
 * {@code available_for_pickup} → out_for_delivery, {@code unknown} → ignored. The EasyPost event id is the OMS
 * {@code eventId}, so a redelivered webhook is a no-op; the shipment reference ({@code result.shipment_id}) is forwarded
 * so the OMS shipment is found even when two shipments share a tracking number.
 */
public final class EasyPostTrackerMapper {
    private EasyPostTrackerMapper() {}

    public static final Map<String, String> STATUS = Map.ofEntries(
        Map.entry("pre_transit", "in_transit"), Map.entry("in_transit", "in_transit"), Map.entry("out_for_delivery", "out_for_delivery"),
        Map.entry("available_for_pickup", "out_for_delivery"), Map.entry("delivered", "delivered"), Map.entry("failure", "exception"),
        Map.entry("error", "exception"), Map.entry("cancelled", "exception"), Map.entry("return_to_sender", "returned_to_sender"));

    /** Returns the OMS carrier body, or {@code null} when the event is not a tracker status the OMS acts on. */
    public static Map<String, Object> toCarrierEvent(Map<String, Object> event) {
        String description = str(event.get("description"));
        if (description == null) throw new ValidationException("EasyPost event has no description");
        if (!description.startsWith("tracker.")) return null;
        Map<String, Object> result = event.get("result") instanceof Map<?, ?> m ? cast(m) : Map.of();
        String tracking = str(result.get("tracking_code"));
        if (tracking == null) throw new ValidationException("EasyPost tracker event has no result.tracking_code");
        String status = str(result.get("status"));
        String type = status == null ? null : STATUS.get(status.toLowerCase(Locale.ROOT));
        if (type == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("eventId", event.get("id") == null ? "easypost:" + tracking + ":" + status + ":" + nz(result.get("updated_at")) : "easypost:" + event.get("id"));
        out.put("type", type);
        out.put("tracking", tracking);
        if (result.get("carrier") != null) out.put("carrier", result.get("carrier"));
        if (result.get("est_delivery_date") != null) out.put("eta", result.get("est_delivery_date"));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) { return (Map<String, Object>) m; }
    private static String str(Object o) { return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o); }
    private static String nz(Object o) { return o == null ? "" : String.valueOf(o); }
}
