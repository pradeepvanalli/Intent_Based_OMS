package ai.intentoms.statemachine.service;

import ai.intentoms.statemachine.model.QuantityBucket;
import ai.intentoms.statemachine.model.StateDef;
import ai.intentoms.statemachine.model.StateModel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure quantity-bucket arithmetic for quantity-level status (FR-SM-02) and the dominant-state rule. */
public final class QuantityBuckets {
    private QuantityBuckets() {}

    public static Map<String, Integer> toMap(List<QuantityBucket> buckets) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (QuantityBucket b : buckets) if (b.qty() > 0) m.merge(b.state(), b.qty(), Integer::sum);
        return m;
    }

    public static int total(Map<String, Integer> buckets) {
        int t = 0;
        for (int q : buckets.values()) t += q;
        return t;
    }

    /**
     * Move {@code qty} units from one state to another, returning a new map without zero buckets.
     * @throws IllegalArgumentException when qty is not positive or exceeds what sits in {@code from}
     */
    public static Map<String, Integer> move(Map<String, Integer> buckets, String from, String to, int qty) {
        if (qty <= 0) throw new IllegalArgumentException("qty must be positive, got " + qty);
        int available = buckets.getOrDefault(from, 0);
        if (available < qty) throw new IllegalArgumentException("insufficient quantity in state '" + from + "': have " + available + ", requested " + qty);
        Map<String, Integer> out = new LinkedHashMap<>(buckets);
        if (available == qty) out.remove(from); else out.put(from, available - qty);
        if (!from.equals(to)) out.merge(to, qty, Integer::sum);
        else out.merge(from, qty, Integer::sum);
        return out;
    }

    /**
     * Dominant state: the non-terminal state holding the most quantity; when every unit is terminal, the terminal state
     * with the most quantity. Ties resolve by model declaration order. Empty buckets return the model's initial state.
     */
    public static String dominant(StateModel model, Map<String, Integer> buckets) {
        String bestActive = null, bestTerminal = null;
        int activeQty = -1, terminalQty = -1;
        for (var e : buckets.entrySet()) {
            if (e.getValue() <= 0) continue;
            boolean terminal = model.isTerminal(e.getKey());
            if (terminal) {
                if (e.getValue() > terminalQty || (e.getValue() == terminalQty && model.indexOf(e.getKey()) < model.indexOf(bestTerminal))) {
                    bestTerminal = e.getKey(); terminalQty = e.getValue();
                }
            } else if (e.getValue() > activeQty || (e.getValue() == activeQty && model.indexOf(e.getKey()) < model.indexOf(bestActive))) {
                bestActive = e.getKey(); activeQty = e.getValue();
            }
        }
        if (bestActive != null) return bestActive;
        if (bestTerminal != null) return bestTerminal;
        return model.initial().id();
    }

    /** CEL {@code state} root: every model state id → qty as long (zero-filled) plus any extra keys in {@code buckets}. */
    public static Map<String, Object> celStateMap(StateModel model, Map<String, Integer> buckets) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (StateDef s : model.states()) m.put(s.id(), 0L);
        for (var e : buckets.entrySet()) m.put(e.getKey(), e.getValue().longValue());
        return m;
    }
}
