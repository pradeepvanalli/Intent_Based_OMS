package ai.intentoms.copilot.change;

import ai.intentoms.platform.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Merges several proposed versions of the same decision into one draft (one draft per decision when a change set holds
 * more than one change for it). Each change is reduced to its delta against the current YAML at top-level key
 * granularity and the deltas are applied in order onto the first proposal; {@code rules} are merged by rule id so two
 * changes that each add a rule both survive. Deterministic and comment-free (the studio normalises drafts the same way).
 */
public final class DecisionYamlMerge {
    private DecisionYamlMerge() {}

    public static String merge(String before, List<String> afters) {
        if (afters.size() == 1) return afters.get(0);
        Map<String, Object> base = Json.yamlMap(afters.get(0));
        Map<String, Object> current = before == null ? Map.of() : Json.yamlMap(before);
        for (int i = 1; i < afters.size(); i++) {
            Map<String, Object> after = Json.yamlMap(afters.get(i));
            for (var e : after.entrySet()) {
                Object was = current.get(e.getKey());
                if (Objects.equals(was, e.getValue())) continue;              // unchanged by this proposal
                if (e.getKey().equals("rules")) base.put("rules", mergeRules(base.get("rules"), was, e.getValue()));
                else base.put(e.getKey(), e.getValue());
            }
            for (String removed : current.keySet()) if (!after.containsKey(removed) && !removed.equals("rules")) base.remove(removed);
        }
        return Json.toYaml(base);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> mergeRules(Object baseRules, Object beforeRules, Object afterRules) {
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        if (baseRules instanceof List<?> l) for (Object r : l) if (r instanceof Map<?, ?> m) byId.put(id(m), (Map<String, Object>) m);
        Map<String, Map<String, Object>> was = new LinkedHashMap<>();
        if (beforeRules instanceof List<?> l) for (Object r : l) if (r instanceof Map<?, ?> m) was.put(id(m), (Map<String, Object>) m);
        Map<String, Map<String, Object>> now = new LinkedHashMap<>();
        if (afterRules instanceof List<?> l) for (Object r : l) if (r instanceof Map<?, ?> m) now.put(id(m), (Map<String, Object>) m);
        for (var e : now.entrySet()) if (!Objects.equals(was.get(e.getKey()), e.getValue())) byId.put(e.getKey(), e.getValue());   // added or edited
        for (String id : was.keySet()) if (!now.containsKey(id)) byId.remove(id);                                               // removed
        return new ArrayList<>(byId.values());
    }

    private static String id(Map<?, ?> rule) { return rule.get("id") == null ? Json.canonical(rule) : String.valueOf(rule.get("id")); }
}
