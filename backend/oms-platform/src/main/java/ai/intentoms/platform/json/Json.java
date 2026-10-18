package ai.intentoms.platform.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One shared, deterministic JSON/YAML configuration. Decision input hashes rely on sorted keys. */
public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    public static final ObjectMapper YAML = new ObjectMapper(YAMLFactory.builder().enable(YAMLGenerator.Feature.MINIMIZE_QUOTES).disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER).build())
        .registerModule(new JavaTimeModule())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final TypeReference<List<Object>> LIST = new TypeReference<>() {};
    private static final TypeReference<Set<String>> STRSET = new TypeReference<>() {};

    private Json() {}

    public static String write(Object o) {
        try { return MAPPER.writeValueAsString(o); } catch (JsonProcessingException e) { throw new IllegalArgumentException(e); }
    }

    /** Writes against a declared type. Needed for collections of a polymorphic type: an erased {@code List} loses the
     * element type's {@code @JsonTypeInfo}, so the type id would be dropped and the value could not be read back. */
    public static String write(Object o, TypeReference<?> type) {
        try { return MAPPER.writerFor(type).writeValueAsString(o); } catch (JsonProcessingException e) { throw new IllegalArgumentException(e); }
    }

    public static <T> T read(String json, Class<T> type) {
        try { return MAPPER.readValue(json, type); } catch (IOException e) { throw new IllegalArgumentException("Bad JSON for " + type.getSimpleName() + ": " + e.getMessage(), e); }
    }

    public static <T> T read(String json, TypeReference<T> type) {
        try { return MAPPER.readValue(json, type); } catch (IOException e) { throw new IllegalArgumentException("Bad JSON: " + e.getMessage(), e); }
    }

    public static JsonNode tree(String json) {
        try { return json == null ? MAPPER.nullNode() : MAPPER.readTree(json); } catch (IOException e) { throw new IllegalArgumentException(e); }
    }

    public static JsonNode toTree(Object o) { return MAPPER.valueToTree(o); }

    public static Map<String, Object> toMap(Object o) {
        if (o == null) return new LinkedHashMap<>();
        if (o instanceof String s) return read(s, MAP);
        return MAPPER.convertValue(o, MAP);
    }

    public static Map<String, Object> readMap(String json) { return json == null || json.isBlank() ? new LinkedHashMap<>() : read(json, MAP); }
    public static List<Object> readList(String json) { return json == null || json.isBlank() ? List.of() : read(json, LIST); }
    public static Set<String> readSet(String json) { return json == null || json.isBlank() ? Set.of() : read(json, STRSET); }

    public static <T> T convert(Object o, Class<T> type) { return MAPPER.convertValue(o, type); }
    public static <T> T convert(Object o, TypeReference<T> type) { return MAPPER.convertValue(o, type); }

    public static <T> T yaml(String yaml, Class<T> type) {
        try { return YAML.readValue(yaml, type); } catch (IOException e) { throw new IllegalArgumentException("Bad YAML for " + type.getSimpleName() + ": " + e.getMessage(), e); }
    }

    public static <T> T yaml(InputStream in, Class<T> type) {
        try { return YAML.readValue(in, type); } catch (IOException e) { throw new IllegalArgumentException("Bad YAML for " + type.getSimpleName() + ": " + e.getMessage(), e); }
    }

    public static Map<String, Object> yamlMap(String yaml) {
        try { return YAML.readValue(yaml, MAP); } catch (IOException e) { throw new IllegalArgumentException("Bad YAML: " + e.getMessage(), e); }
    }

    public static String toYaml(Object o) {
        try { return YAML.writeValueAsString(o); } catch (JsonProcessingException e) { throw new IllegalArgumentException(e); }
    }

    /** Canonical (sorted-key) JSON for hashing. */
    public static String canonical(Object o) {
        try {
            JsonNode n = o instanceof JsonNode j ? j : MAPPER.valueToTree(o);
            return MAPPER.writeValueAsString(sort(n));
        } catch (JsonProcessingException e) { throw new IllegalArgumentException(e); }
    }

    private static JsonNode sort(JsonNode n) {
        if (n.isObject()) {
            ObjectNode out = MAPPER.createObjectNode();
            java.util.TreeMap<String, JsonNode> sorted = new java.util.TreeMap<>();
            n.fields().forEachRemaining(e -> sorted.put(e.getKey(), sort(e.getValue())));
            sorted.forEach(out::set);
            return out;
        }
        if (n.isArray()) {
            var arr = MAPPER.createArrayNode();
            n.forEach(e -> arr.add(sort(e)));
            return arr;
        }
        return n;
    }

    public static String sha256(Object o) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(canonical(o).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** Dotted-path lookup into nested maps/lists: "order.lines[0].sku" or "customer.tier". */
    @SuppressWarnings("unchecked")
    public static Object path(Object root, String path) {
        Object cur = root;
        for (String seg : path.split("\\.")) {
            if (cur == null) return null;
            String key = seg;
            Integer idx = null;
            int b = seg.indexOf('[');
            if (b >= 0 && seg.endsWith("]")) {
                key = seg.substring(0, b);
                String i = seg.substring(b + 1, seg.length() - 1);
                idx = i.isEmpty() ? null : Integer.parseInt(i);
            }
            if (!key.isEmpty()) {
                if (cur instanceof Map<?, ?> m) cur = m.get(key);
                else if (cur instanceof JsonNode jn) cur = jn.get(key);
                else return null;
            }
            if (idx != null) {
                if (cur instanceof List<?> l) cur = idx < l.size() ? l.get(idx) : null;
                else if (cur instanceof JsonNode jn) cur = jn.get(idx);
                else return null;
            }
        }
        if (cur instanceof JsonNode jn) return MAPPER.convertValue(jn, Object.class);
        return cur;
    }
}
