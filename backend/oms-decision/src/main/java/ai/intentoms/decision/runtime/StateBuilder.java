package ai.intentoms.decision.runtime;

import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.pii.Tokenizer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Builds the minimal typed state sent to a provider (GR-09, section 9 rule 2): only the {@code state.include} paths are
 * copied from the input ({@code plan_options[].eta} projects a field out of every element of an array) and leaf keys
 * that look like PII (name, email, phone, address, street, first_name, last_name) are tokenised deterministically.
 * With no include list the whole input is passed, tokenised.
 */
@Component
public class StateBuilder {
    public static final Pattern PII_KEY = Pattern.compile("(?i)^(.*_)?(name|email|phone|address|street|first_name|last_name|given_name|family_name)(_.*)?$");
    private final Tokenizer tokenizer;

    public StateBuilder(Tokenizer tokenizer) { this.tokenizer = tokenizer; }

    public Map<String, Object> build(DecisionDefinition def, Map<String, Object> input) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (input == null) return out;
        if (def.stateInclude().isEmpty()) return tokenise(deepCopy(input));
        for (String path : def.stateInclude()) project(input, path, out);
        return tokenise(out);
    }

    /** Copies the value(s) at {@code path} from {@code src} into {@code dst}, expanding {@code []} wildcards. */
    @SuppressWarnings("unchecked")
    void project(Object src, String path, Map<String, Object> dst) {
        int wc = path.indexOf("[].");
        int wcEnd = path.endsWith("[]") ? path.length() - 2 : -1;
        if (wc < 0 && wcEnd < 0) {
            Object v = Json.path(src, path);
            if (v != null) setPath(dst, path, deepCopy(v));
            return;
        }
        String head = wc >= 0 ? path.substring(0, wc) : path.substring(0, wcEnd);
        String rest = wc >= 0 ? path.substring(wc + 3) : null;
        Object listObj = Json.path(src, head);
        if (!(listObj instanceof List<?> list)) return;
        List<Object> target = (List<Object>) ensureList(dst, head, list.size());
        for (int i = 0; i < list.size(); i++) {
            Object elem = list.get(i);
            if (rest == null) { target.set(i, deepCopy(elem)); continue; }
            Object existing = target.get(i);
            Map<String, Object> elemDst = existing instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
            project(elem, rest, elemDst);
            target.set(i, elemDst);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object ensureList(Map<String, Object> dst, String path, int size) {
        String[] segs = path.split("\\.");
        Map<String, Object> cur = dst;
        for (int i = 0; i < segs.length - 1; i++) cur = (Map<String, Object>) cur.computeIfAbsent(segs[i], k -> new LinkedHashMap<String, Object>());
        List<Object> list = (List<Object>) cur.computeIfAbsent(segs[segs.length - 1], k -> new ArrayList<>());
        while (list.size() < size) list.add(null);
        return list;
    }

    @SuppressWarnings("unchecked")
    static void setPath(Map<String, Object> dst, String path, Object value) {
        String[] segs = path.split("\\.");
        Map<String, Object> cur = dst;
        for (int i = 0; i < segs.length - 1; i++) {
            String seg = segs[i];
            int b = seg.indexOf('[');
            if (b >= 0) {
                String key = seg.substring(0, b);
                int idx = Integer.parseInt(seg.substring(b + 1, seg.length() - 1));
                List<Object> list = (List<Object>) cur.computeIfAbsent(key, k -> new ArrayList<>());
                while (list.size() <= idx) list.add(null);
                if (!(list.get(idx) instanceof Map)) list.set(idx, new LinkedHashMap<String, Object>());
                cur = (Map<String, Object>) list.get(idx);
            } else cur = (Map<String, Object>) cur.computeIfAbsent(seg, k -> new LinkedHashMap<String, Object>());
        }
        String last = segs[segs.length - 1];
        int b = last.indexOf('[');
        if (b >= 0) {
            String key = last.substring(0, b);
            int idx = Integer.parseInt(last.substring(b + 1, last.length() - 1));
            List<Object> list = (List<Object>) cur.computeIfAbsent(key, k -> new ArrayList<>());
            while (list.size() <= idx) list.add(null);
            list.set(idx, value);
        } else cur.put(last, value);
    }

    /** Replaces PII-looking string leaves with deterministic tokens (GR-09). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> tokenise(Map<String, Object> m) {
        for (var e : m.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Map<?, ?> im) tokenise((Map<String, Object>) im);
            else if (v instanceof List<?> l) tokeniseList((List<Object>) l, e.getKey());
            else if (v instanceof String s && PII_KEY.matcher(e.getKey()).matches()) e.setValue(tokenizer.token(kind(e.getKey()), s));
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private void tokeniseList(List<Object> l, String key) {
        for (int i = 0; i < l.size(); i++) {
            Object v = l.get(i);
            if (v instanceof Map<?, ?> im) tokenise((Map<String, Object>) im);
            else if (v instanceof List<?> il) tokeniseList((List<Object>) il, key);
            else if (v instanceof String s && PII_KEY.matcher(key).matches()) l.set(i, tokenizer.token(kind(key), s));
        }
    }

    static String kind(String key) {
        String k = key.toLowerCase();
        if (k.contains("email")) return "em";
        if (k.contains("phone")) return "ph";
        if (k.contains("address") || k.contains("street")) return "ad";
        return "nm";
    }

    @SuppressWarnings("unchecked")
    static Object deepCopy(Object o) {
        if (o instanceof Map<?, ?> m) { Map<String, Object> c = new LinkedHashMap<>(); m.forEach((k, v) -> c.put(String.valueOf(k), deepCopy(v))); return c; }
        if (o instanceof List<?> l) { List<Object> c = new ArrayList<>(l.size()); l.forEach(v -> c.add(deepCopy(v))); return c; }
        return o;
    }

    static Map<String, Object> deepCopy(Map<String, Object> m) { return (Map<String, Object>) deepCopy((Object) m); }
}
