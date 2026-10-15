package ai.intentoms.simulation.margin;

import ai.intentoms.platform.error.ValidationException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Knobs of a Margin Replay import (UI page {@code /replay}).
 *
 * @param format              auto | shopify | generic
 * @param assumeStock         every active node is treated as holding stock of every SKU, so the comparison is about network
 *                            geometry, node economics and policy rather than today's inventory snapshot (default true)
 * @param unknownSkuCogsRatio COGS assumed for SKUs the catalogue does not know, as a share of the selling price (default 0.6)
 * @param baseline            {@code nearest_node} (default) or {@code single_dc:<nodeId>}
 * @param useDecisions        run the tenant's {@code fulfilment_intent} decision (simulation mode) on orders with notes
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MarginReplayOptions(String format, Boolean assumeStock, Double unknownSkuCogsRatio, String baseline, Boolean useDecisions) {
    public static final String NEAREST_NODE = "nearest_node";
    public static final String SINGLE_DC_PREFIX = "single_dc:";

    public MarginReplayOptions {
        format = format == null || format.isBlank() ? "auto" : format.trim().toLowerCase(Locale.ROOT);
        assumeStock = assumeStock == null || assumeStock;
        unknownSkuCogsRatio = unknownSkuCogsRatio == null ? 0.6 : Math.max(0.0, Math.min(1.0, unknownSkuCogsRatio));
        baseline = baseline == null || baseline.isBlank() ? NEAREST_NODE : baseline.trim();
        useDecisions = useDecisions == null || useDecisions;
        if (!baseline.equals(NEAREST_NODE) && !(baseline.startsWith(SINGLE_DC_PREFIX) && baseline.length() > SINGLE_DC_PREFIX.length())) {
            throw new ValidationException("baseline must be 'nearest_node' or 'single_dc:<nodeId>'", Map.of("baseline", baseline));
        }
    }

    public static MarginReplayOptions defaults() { return new MarginReplayOptions(null, null, null, null, null); }

    public boolean singleDc() { return baseline.startsWith(SINGLE_DC_PREFIX); }

    /** The node id of a {@code single_dc:<nodeId>} baseline; null for nearest-node. */
    public String singleDcNode() { return singleDc() ? baseline.substring(SINGLE_DC_PREFIX.length()).trim() : null; }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", format);
        m.put("assumeStock", assumeStock);
        m.put("unknownSkuCogsRatio", unknownSkuCogsRatio);
        m.put("baseline", baseline);
        m.put("useDecisions", useDecisions);
        return m;
    }

    /** Builds options from loose request parameters (multipart form fields), falling back to the defaults. */
    public static MarginReplayOptions fromParams(Map<String, String> p) {
        if (p == null) return defaults();
        return new MarginReplayOptions(p.get("format"), bool(p.get("assumeStock")), num(p.get("unknownSkuCogsRatio")), p.get("baseline"), bool(p.get("useDecisions")));
    }

    private static Boolean bool(String s) { return s == null || s.isBlank() ? null : Boolean.parseBoolean(s.trim()); }

    private static Double num(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { throw new ValidationException("unknownSkuCogsRatio must be a number between 0 and 1", Map.of("value", s)); }
    }
}
