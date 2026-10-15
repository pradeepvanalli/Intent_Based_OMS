package ai.intentoms.agent.screen;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Result of the injection screen (GR-08). {@code heuristic} is true when the decision fabric fell back to its default
 * and the regex screen decided; {@code decisionRecordId} links the evidence pack to the record.
 */
public record ScreenResult(String source, double probability, boolean flagged, String decisionRecordId, String layer, boolean heuristic) {
    public static ScreenResult clean(String source) { return new ScreenResult(source, 0.0, false, null, null, false); }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", source);
        m.put("probability", probability);
        m.put("flagged", flagged);
        if (decisionRecordId != null) m.put("recordId", decisionRecordId);
        if (layer != null) m.put("layer", layer);
        m.put("heuristic", heuristic);
        return m;
    }
}
