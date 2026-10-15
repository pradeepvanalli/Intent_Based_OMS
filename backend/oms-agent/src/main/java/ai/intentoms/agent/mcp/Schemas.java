package ai.intentoms.agent.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small JSON Schema (2020-12) builders for tool input schemas. */
public final class Schemas {
    private Schemas() {}

    public static Map<String, Object> object(Map<String, Object> properties, List<String> required) { return object(properties, required, false); }

    public static Map<String, Object> object(Map<String, Object> properties, List<String> required, boolean additional) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        m.put("properties", properties);
        if (required != null && !required.isEmpty()) m.put("required", required);
        m.put("additionalProperties", additional);
        return m;
    }

    public static Map<String, Object> string(String description) { return typed("string", description); }

    public static Map<String, Object> string(String description, List<String> enumValues) {
        Map<String, Object> m = typed("string", description);
        m.put("enum", enumValues);
        return m;
    }

    public static Map<String, Object> integer(String description, Integer minimum, Integer maximum) {
        Map<String, Object> m = typed("integer", description);
        if (minimum != null) m.put("minimum", minimum);
        if (maximum != null) m.put("maximum", maximum);
        return m;
    }

    public static Map<String, Object> number(String description) { return typed("number", description); }

    public static Map<String, Object> bool(String description) { return typed("boolean", description); }

    public static Map<String, Object> array(Map<String, Object> items, String description, Integer minItems) {
        Map<String, Object> m = typed("array", description);
        m.put("items", items);
        if (minItems != null) m.put("minItems", minItems);
        return m;
    }

    public static Map<String, Object> money(String description) {
        return object(props("amount", number("Amount"), "currency", string("ISO 4217 code")), List.of("amount", "currency"));
    }

    /** Free-form object (validated downstream). */
    public static Map<String, Object> anyObject(String description) {
        Map<String, Object> m = typed("object", description);
        m.put("additionalProperties", true);
        return m;
    }

    public static Map<String, Object> props(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static Map<String, Object> typed(String type, String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        if (description != null) m.put("description", description);
        return m;
    }
}
