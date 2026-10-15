package ai.intentoms.integrations.address;

import ai.intentoms.order.capture.CaptureRequest;
import ai.intentoms.order.validation.AddressVerification;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure mapping of a Smarty US Street API response onto {@link AddressVerification} (FR-CAP-05; section 11 Address
 * validation P0). Rules:
 * <ul>
 *   <li>empty candidate list → {@code invalid} ("no match");</li>
 *   <li>{@code analysis.dpv_match_code} {@code Y} (confirmed), {@code S} (confirmed, secondary dropped), {@code D}
 *       (confirmed, secondary missing) → {@code verified}; {@code N} → {@code undeliverable}; anything else → {@code unverified};</li>
 *   <li>{@code metadata.rdi} Residential / Commercial → {@code addressType}; a PO box record type ({@code P}) → {@code po_box};</li>
 *   <li>{@code metadata.latitude/longitude} → coordinates; {@code components.zipcode + plus4_code} and
 *       {@code analysis.footnotes} become messages so CS sees what Smarty changed.</li>
 * </ul>
 */
public final class SmartyResponseMapper {
    private SmartyResponseMapper() {}

    public static AddressVerification map(List<Map<String, Object>> candidates, CaptureRequest.AddressInput input) {
        if (candidates == null || candidates.isEmpty()) {
            return new AddressVerification("invalid", input.addressType(), null, null, List.of("Smarty found no matching US address"));
        }
        Map<String, Object> c = candidates.get(0);
        Map<String, Object> analysis = sub(c, "analysis");
        Map<String, Object> metadata = sub(c, "metadata");
        Map<String, Object> components = sub(c, "components");
        String dpv = str(analysis.get("dpv_match_code"));
        String status = switch (dpv == null ? "" : dpv.toUpperCase(Locale.ROOT)) {
            case "Y", "S", "D" -> "verified";
            case "N" -> "undeliverable";
            default -> "unverified";
        };
        String addressType = addressType(metadata, input.addressType());
        Double lat = num(metadata.get("latitude"));
        Double lon = num(metadata.get("longitude"));
        List<String> messages = new ArrayList<>();
        String zip = str(components.get("zipcode"));
        String plus4 = str(components.get("plus4_code"));
        if (zip != null) messages.add("ZIP " + zip + (plus4 == null ? "" : "-" + plus4));
        if (c.get("delivery_line_1") != null) messages.add("Standardised: " + c.get("delivery_line_1") + (c.get("last_line") == null ? "" : ", " + c.get("last_line")));
        if ("S".equalsIgnoreCase(dpv)) messages.add("Secondary (unit) information was not recognised and dropped");
        if ("D".equalsIgnoreCase(dpv)) messages.add("Secondary (unit) information is missing");
        if ("N".equalsIgnoreCase(dpv)) messages.add("USPS reports the address as not deliverable");
        if (Boolean.TRUE.equals(analysis.get("dpv_vacant")) || "Y".equalsIgnoreCase(str(analysis.get("dpv_vacant")))) messages.add("Address is flagged vacant");
        if (analysis.get("footnotes") != null) messages.add("Footnotes " + analysis.get("footnotes"));
        return new AddressVerification(status, addressType, lat, lon, messages);
    }

    static String addressType(Map<String, Object> metadata, String fallback) {
        String recordType = str(metadata.get("record_type"));
        if ("P".equalsIgnoreCase(recordType)) return "po_box";
        String rdi = str(metadata.get("rdi"));
        if (rdi == null) return fallback;
        return switch (rdi.toLowerCase(Locale.ROOT)) {
            case "residential" -> "residential";
            case "commercial" -> "commercial";
            default -> fallback;
        };
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> sub(Map<String, Object> m, String key) { return m.get(key) instanceof Map<?, ?> s ? (Map<String, Object>) s : Map.of(); }

    static String str(Object o) { return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o); }

    static Double num(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o == null) return null;
        try { return Double.parseDouble(String.valueOf(o)); } catch (NumberFormatException e) { return null; }
    }
}
