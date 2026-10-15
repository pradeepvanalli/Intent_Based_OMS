package ai.intentoms.copilot.llm;

import ai.intentoms.platform.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Strict JSON schema builder for structured model output (GR-04: typed parse and validation, never free text).
 * Every object lists all properties as {@code required} with {@code additionalProperties: false}, which is what the
 * OpenAI-compatible {@code response_format: json_schema, strict: true} contract demands. {@link #validate} re-checks
 * the model's answer against the same schema before any caller reads it.
 */
public final class JsonSchema {
    private final String name;
    private final Map<String, Object> schema;
    private final com.networknt.schema.JsonSchema compiled;

    private JsonSchema(String name, Map<String, Object> schema) {
        this.name = name;
        this.schema = schema;
        this.compiled = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(Json.toTree(schema));
    }

    public static JsonSchema named(String name, Map<String, Object> rootObject) { return new JsonSchema(name, rootObject); }

    public String name() { return name; }
    public Map<String, Object> schema() { return schema; }

    /** Returns the validation messages; empty when the node conforms. */
    public List<String> validate(JsonNode node) {
        Set<ValidationMessage> messages = compiled.validate(node);
        List<String> out = new ArrayList<>();
        for (ValidationMessage m : messages) out.add(m.getMessage());
        return out;
    }

    // ------------------------------------------------------------------ fragment builders

    /** An object whose properties are all required and closed to extra keys. Pass name/schema pairs in order. */
    public static Map<String, Object> object(Object... nameSchemaPairs) {
        if (nameSchemaPairs.length % 2 != 0) throw new IllegalArgumentException("name/schema pairs expected");
        Map<String, Object> props = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (int i = 0; i < nameSchemaPairs.length; i += 2) {
            String n = (String) nameSchemaPairs[i];
            props.put(n, nameSchemaPairs[i + 1]);
            required.add(n);
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("type", "object");
        o.put("properties", props);
        o.put("required", required);
        o.put("additionalProperties", false);
        return o;
    }

    public static Map<String, Object> string(String description) { return typed("string", description); }
    public static Map<String, Object> nullableString(String description) { return typed(List.of("string", "null"), description); }
    public static Map<String, Object> bool(String description) { return typed("boolean", description); }
    public static Map<String, Object> number(String description) { return typed("number", description); }

    public static Map<String, Object> integer(String description, Integer min, Integer max) {
        Map<String, Object> m = typed("integer", description);
        if (min != null) m.put("minimum", min);
        if (max != null) m.put("maximum", max);
        return m;
    }

    public static Map<String, Object> nullableInteger(String description, Integer min, Integer max) {
        Map<String, Object> m = typed(List.of("integer", "null"), description);
        if (min != null) m.put("minimum", min);
        if (max != null) m.put("maximum", max);
        return m;
    }

    public static Map<String, Object> enumOf(String description, List<String> values) {
        Map<String, Object> m = typed("string", description);
        m.put("enum", new ArrayList<>(values));
        return m;
    }

    public static Map<String, Object> enumOf(String description, String... values) { return enumOf(description, Arrays.asList(values)); }

    public static Map<String, Object> array(String description, Map<String, Object> items) {
        Map<String, Object> m = typed("array", description);
        m.put("items", items);
        return m;
    }

    private static Map<String, Object> typed(Object type, String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        if (description != null) m.put("description", description);
        return m;
    }
}
