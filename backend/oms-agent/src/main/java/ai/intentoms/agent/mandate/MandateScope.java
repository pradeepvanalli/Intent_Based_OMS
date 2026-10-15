package ai.intentoms.agent.mandate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What a mandate authorises (AP2-style intent mandate, FR-CAP-07): the merchant, product categories and the maximum
 * number of items. Serialised to the UI as a list of labels ({@code merchant:demo}, {@code category:apparel}, {@code max_items:3}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MandateScope(String merchant, List<String> categories, Integer maxItems) {
    public MandateScope {
        categories = categories == null ? List.of() : List.copyOf(categories);
    }

    public static MandateScope empty() { return new MandateScope(null, List.of(), null); }

    /** Accepts the object form or the UI's label list ({@code ["merchant:demo", "category:apparel", "max_items:3"]}). */
    @SuppressWarnings("unchecked")
    public static MandateScope parse(Object raw) {
        if (raw == null) return empty();
        if (raw instanceof MandateScope s) return s;
        if (raw instanceof Map<?, ?> m) {
            Map<String, Object> map = (Map<String, Object>) m;
            Object cats = map.get("categories");
            List<String> categories = cats instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
            Object max = map.get("maxItems") == null ? map.get("max_items") : map.get("maxItems");
            return new MandateScope(map.get("merchant") == null ? null : String.valueOf(map.get("merchant")), categories, max instanceof Number n ? n.intValue() : max == null ? null : Integer.parseInt(max.toString()));
        }
        if (raw instanceof List<?> labels) {
            String merchant = null; Integer max = null; List<String> cats = new ArrayList<>();
            for (Object o : labels) {
                String s = String.valueOf(o);
                int i = s.indexOf(':');
                String k = i < 0 ? s : s.substring(0, i).trim().toLowerCase();
                String v = i < 0 ? null : s.substring(i + 1).trim();
                switch (k) {
                    case "merchant" -> merchant = v;
                    case "category", "categories" -> { if (v != null) cats.add(v); }
                    case "max_items", "maxitems" -> max = v == null ? null : Integer.parseInt(v);
                    default -> cats.add(s);
                }
            }
            return new MandateScope(merchant, cats, max);
        }
        throw new IllegalArgumentException("scope must be an object or a list of labels");
    }

    public List<String> labels() {
        List<String> out = new ArrayList<>();
        if (merchant != null && !merchant.isBlank()) out.add("merchant:" + merchant);
        for (String c : categories) out.add("category:" + c);
        if (maxItems != null) out.add("max_items:" + maxItems);
        return out;
    }
}
