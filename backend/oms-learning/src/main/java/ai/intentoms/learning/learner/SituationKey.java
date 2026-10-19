package ai.intentoms.learning.learner;

import ai.intentoms.platform.json.Json;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * "The same situation" for DF-03 and DF-04: two decisions are the same situation when what the decision reads (its
 * {@code state.include} paths) is the same once identifiers are dropped and values normalised. Grouping by the raw input
 * hash never matched anything live, because every input carries its own order id. Numbers keep two significant digits
 * (102 and 104 are the same, 100 and 250 are not); text is trimmed and lower-cased; keys that only identify something
 * ({@code id}, {@code ref}, {@code token}, {@code ..._id}, {@code ...Ref}) are left out.
 */
public final class SituationKey {
    private SituationKey() {}

    /** What the decision reads: the include paths (all of the input when none are declared), identifiers removed. */
    public static Map<String, Object> leaves(Map<String, Object> input, List<String> includes) {
        Map<String, Object> out = new TreeMap<>();
        if (input == null) return out;
        if (includes == null || includes.isEmpty()) flatten("", input, out);
        else for (String path : includes) {
            String plain = path.replaceAll("\\[\\d*]", "");
            Object v = Json.path(input, plain);
            if (v == null) v = input.get(plain);
            if (v != null) flatten(plain, v, out);
        }
        out.keySet().removeIf(SituationKey::identifier);
        return out;
    }

    /** The normalised situation as a stable hash. */
    public static String key(Map<String, Object> input, List<String> includes) {
        Map<String, Object> normal = new TreeMap<>();
        leaves(input, includes).forEach((k, v) -> normal.put(k, normalise(v)));
        return Json.sha256(new LinkedHashMap<>(normal));
    }

    static Object normalise(Object v) {
        if (v instanceof Number n) {
            double d = n.doubleValue();
            if (d == 0 || Double.isNaN(d) || Double.isInfinite(d)) return 0;
            return new BigDecimal(d).round(new MathContext(2)).doubleValue();
        }
        if (v instanceof String s) return s.trim().toLowerCase(Locale.ROOT);
        return v;
    }

    static boolean identifier(String path) {
        String last = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
        String l = last.toLowerCase(Locale.ROOT);
        return l.equals("id") || l.equals("ref") || l.equals("token") || l.equals("recordid") || l.endsWith("_id") || l.endsWith("_ref") || l.endsWith("_token")
            || last.endsWith("Id") || last.endsWith("Ref") || last.endsWith("Token");
    }

    @SuppressWarnings("unchecked")
    private static void flatten(String prefix, Object v, Map<String, Object> out) {
        if (v instanceof Map<?, ?> m) {
            ((Map<String, Object>) m).forEach((k, x) -> flatten(prefix.isEmpty() ? k : prefix + "." + k, x, out));
        } else if (v instanceof List<?> l) {
            out.put(prefix, l.stream().map(String::valueOf).sorted().toList().toString());
        } else {
            out.put(prefix, v);
        }
    }

    /**
     * A CEL condition that matches every input of the group (for a drafted rule): equality on the leaves they all share,
     * a range on numbers that vary within the group. Leaves that vary otherwise are left out.
     */
    public static String when(List<Map<String, Object>> inputs, List<String> includes) {
        if (inputs.isEmpty()) return "true";
        List<Map<String, Object>> all = inputs.stream().map(i -> leaves(i, includes)).toList();
        List<String> clauses = new ArrayList<>();
        for (Map.Entry<String, Object> e : all.get(0).entrySet()) {
            String path = e.getKey();
            if (!all.stream().allMatch(m -> m.containsKey(path))) continue;
            List<Object> values = all.stream().map(m -> m.get(path)).toList();
            String ref = "input." + path;
            if (values.stream().allMatch(v -> v instanceof Number)) {
                double min = values.stream().mapToDouble(v -> ((Number) v).doubleValue()).min().orElse(0);
                double max = values.stream().mapToDouble(v -> ((Number) v).doubleValue()).max().orElse(0);
                clauses.add(ref + " >= " + num(min) + " && " + ref + " <= " + num(max)); // a range even for one value: no int/double equality pitfalls
            } else if (values.stream().distinct().count() == 1) {
                Object v = values.get(0);
                if (v instanceof Boolean b) clauses.add(ref + " == " + b);
                else if (v instanceof String s && !s.startsWith("[")) clauses.add(ref + " == '" + s.replace("\\", "\\\\").replace("'", "\\'") + "'");
            }
        }
        return clauses.isEmpty() ? "true" : String.join(" && ", clauses);
    }

    private static String num(double d) { return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d); }
}
