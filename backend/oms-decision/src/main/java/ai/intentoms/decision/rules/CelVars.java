package ai.intentoms.decision.rules;

import ai.intentoms.decision.model.Answer;
import ai.intentoms.platform.cel.CelEngine;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the CEL variable map shared by L0 rules and pre/post checks. {@code input} is always bound; every standard
 * root present at the top level of the input (order, customer, return, plan, chosen_plan, ...) is bound too so Appendix
 * B expressions such as {@code chosen_plan.margin >= tenant.margin_floor} work unchanged; {@code plan} aliases
 * {@code chosen_plan} when only one is present. Extra invocation variables go under {@code ctx}.
 */
public final class CelVars {
    private CelVars() {}

    @SuppressWarnings("unchecked")
    public static Map<String, Object> base(Map<String, Object> input, Map<String, Object> tenant, Map<String, Object> extra) {
        Map<String, Object> vars = new HashMap<>();
        Map<String, Object> in = input == null ? Map.of() : (Map<String, Object>) normalise(input);
        vars.put("input", in);
        for (var e : in.entrySet()) if (CelEngine.STANDARD_ROOTS.contains(e.getKey()) && e.getValue() != null) vars.put(e.getKey(), e.getValue());
        if (!vars.containsKey("plan") && vars.containsKey("chosen_plan")) vars.put("plan", vars.get("chosen_plan"));
        if (!vars.containsKey("chosen_plan") && vars.containsKey("plan")) vars.put("chosen_plan", vars.get("plan"));
        if (tenant != null) vars.put("tenant", normalise(tenant));
        vars.put("ctx", extra == null ? Map.of() : normalise(extra));
        return vars;
    }

    /**
     * cel-java evaluates only int64/double numerics: JSON-parsed Integer, Short, Float and BigDecimal values are widened
     * so {@code input.total > 100} works on any input shape. Maps and lists are copied; other values pass through.
     */
    public static Object normalise(Object o) {
        if (o instanceof Integer || o instanceof Short || o instanceof Byte) return ((Number) o).longValue();
        if (o instanceof Float || o instanceof java.math.BigDecimal) return ((Number) o).doubleValue();
        if (o instanceof java.math.BigInteger bi) return bi.longValue();
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), normalise(v)));
            return out;
        }
        if (o instanceof java.util.List<?> l) {
            java.util.List<Object> out = new java.util.ArrayList<>(l.size());
            l.forEach(v -> out.add(normalise(v)));
            return out;
        }
        return o;
    }

    /** Adds the post-check variables: {@code answers} (key to value) and {@code decision} {value, confidence, band}. */
    public static Map<String, Object> withAnswers(Map<String, Object> base, Map<String, Answer> answers, Object value, double confidence, String band) {
        Map<String, Object> vars = new HashMap<>(base);
        Map<String, Object> a = new LinkedHashMap<>();
        if (answers != null) answers.forEach((k, v) -> a.put(k, normalise(v.value())));
        vars.put("answers", a);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("value", normalise(value));
        d.put("confidence", confidence);
        d.put("band", band);
        vars.put("decision", d);
        return vars;
    }
}
