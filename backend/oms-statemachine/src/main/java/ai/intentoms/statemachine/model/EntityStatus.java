package ai.intentoms.statemachine.model;

import java.util.List;
import java.util.Map;

/**
 * Current status of an entity under its pinned model version (FR-SM-02, FR-SM-06). {@code state} is the dominant
 * state: the non-terminal state holding the most quantity, or, when everything is terminal, the terminal state with
 * the most quantity (ties resolved by declaration order).
 */
public record EntityStatus(String entityType, String entityId, String modelId, int modelVersion, String state,
                           List<QuantityBucket> quantities, Map<String, Object> attributes) {
    public EntityStatus {
        quantities = quantities == null ? List.of() : List.copyOf(quantities);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public int totalQty() { return quantities.stream().mapToInt(QuantityBucket::qty).sum(); }

    public int qtyIn(String stateId) {
        for (QuantityBucket b : quantities) if (b.state().equals(stateId)) return b.qty();
        return 0;
    }

    public Map<String, Integer> qtyMap() {
        Map<String, Integer> m = new java.util.LinkedHashMap<>();
        for (QuantityBucket b : quantities) m.put(b.state(), b.qty());
        return m;
    }
}
