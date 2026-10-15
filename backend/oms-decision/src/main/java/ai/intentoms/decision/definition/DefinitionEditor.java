package ai.intentoms.decision.definition;

import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Structured field editing for the studio (FR-PRM-01): applies dotted-path edits such as
 * {@code questions.intent.criteria.ship_complete}, {@code bands.act}, {@code rules[2].then}, {@code weights.on_time}
 * to the YAML model and re-serialises it, so business users never touch a bare text box.
 */
public final class DefinitionEditor {
    private DefinitionEditor() {}

    /** Applies edits in order and returns the new YAML. A null value removes the field. */
    @SuppressWarnings("unchecked")
    public static String apply(String yaml, Map<String, Object> edits) {
        Map<String, Object> model = new LinkedHashMap<>(Json.yamlMap(yaml));
        for (var e : edits.entrySet()) {
            String path = normalise(e.getKey());
            List<Object> segs = segments(path);
            set(model, segs, e.getValue(), path);
        }
        return Json.toYaml(model);
    }

    /** Shortcuts: bands.act -> bands.act.confidence_gte, bands.confirm -> bands.confirm.confidence_gte, weights.x -> composite.weights.x. */
    static String normalise(String path) {
        if (path.equals("bands.act") || path.equals("bands.confirm")) return path + ".confidence_gte";
        if (path.startsWith("weights.")) return "composite." + path;
        if (path.startsWith("state.") && !path.startsWith("state.include")) return "state.include" + path.substring("state".length());
        return path;
    }

    static List<Object> segments(String path) {
        List<Object> out = new ArrayList<>();
        for (String seg : path.split("\\.")) {
            int b = seg.indexOf('[');
            if (b >= 0) {
                if (b > 0) out.add(seg.substring(0, b));
                String rest = seg.substring(b);
                while (rest.startsWith("[")) {
                    int close = rest.indexOf(']');
                    if (close < 0) throw new ValidationException("Bad edit path " + path);
                    out.add(Integer.parseInt(rest.substring(1, close)));
                    rest = rest.substring(close + 1);
                }
            } else out.add(seg);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void set(Object container, List<Object> segs, Object value, String path) {
        Object seg = segs.get(0);
        boolean last = segs.size() == 1;
        if (container instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = (Map<String, Object>) rawMap;
            String key = String.valueOf(seg);
            if (last) { if (value == null) map.remove(key); else map.put(key, value); return; }
            Object next = map.get(key);
            if (next == null) { next = segs.get(1) instanceof Integer ? new ArrayList<>() : new LinkedHashMap<String, Object>(); map.put(key, next); }
            else if (next instanceof Map<?, ?> nm) { next = new LinkedHashMap<>((Map<String, Object>) nm); map.put(key, next); }
            else if (next instanceof List<?> nl) { next = new ArrayList<>(nl); map.put(key, next); }
            set(next, segs.subList(1, segs.size()), value, path);
        } else if (container instanceof List<?> rawList) {
            List<Object> list = (List<Object>) rawList;
            if (!(seg instanceof Integer idx)) throw new ValidationException("Expected list index in edit path " + path);
            if (idx < 0 || idx > list.size()) throw new ValidationException("Index out of range in edit path " + path);
            if (last) { if (value == null) { if (idx < list.size()) list.remove((int) idx); } else if (idx == list.size()) list.add(value); else list.set(idx, value); return; }
            if (idx == list.size()) list.add(segs.get(1) instanceof Integer ? new ArrayList<>() : new LinkedHashMap<String, Object>());
            Object next = list.get(idx);
            if (next instanceof Map<?, ?> nm) { next = new LinkedHashMap<>((Map<String, Object>) nm); list.set(idx, next); }
            else if (next instanceof List<?> nl) { next = new ArrayList<>(nl); list.set(idx, next); }
            set(next, segs.subList(1, segs.size()), value, path);
        } else throw new ValidationException("Cannot descend into scalar at edit path " + path);
    }
}
