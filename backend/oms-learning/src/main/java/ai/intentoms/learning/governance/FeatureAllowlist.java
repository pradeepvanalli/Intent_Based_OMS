package ai.intentoms.learning.governance;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * GV-07: protected attributes never become features, and a learner reads only the features it declares
 * ({@link LearnerDescriptor#features()}). Names are matched by whole word, split on punctuation and camelCase, so
 * {@code customer.age_band} and {@code buyerGender} are caught while {@code page_views} and {@code usage} are not.
 */
public final class FeatureAllowlist {
    private FeatureAllowlist() {}

    /** Words that mark a protected attribute or a close proxy for one. */
    public static final Set<String> PROTECTED_WORDS = Set.of("gender", "sex", "race", "racial", "ethnicity", "ethnic", "religion", "religious", "age", "dob",
        "birthdate", "birthday", "disability", "disabled", "nationality", "citizenship", "marital", "pregnancy", "pregnant", "orientation", "caste");

    /** "age" of a thing, not a person: stock age, order age, inventory age are ordinary operational features. */
    private static final Set<String> THING_CONTEXT = Set.of("stock", "inventory", "sku", "item", "items", "unit", "units", "package", "lot", "batch", "product",
        "node", "order", "orders", "line", "lines", "shipment", "task", "case", "aged", "reservation");

    public static boolean isProtected(String featureName) {
        if (featureName == null) return false;
        String spaced = featureName.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
        List<String> words = List.of(spaced.split("[^a-z0-9]+"));
        for (String word : words) {
            if (!PROTECTED_WORDS.contains(word)) continue;
            if (word.equals("age") && words.stream().anyMatch(THING_CONTEXT::contains)) continue;
            return true;
        }
        return spaced.contains("date of birth") || spaced.replace(' ', '_').contains("date_of_birth");
    }

    public static List<String> protectedNames(Collection<String> names) {
        List<String> out = new ArrayList<>();
        for (String n : names) if (isProtected(n)) out.add(n);
        return out;
    }

    /** Features a learner may not use: any protected name, plus anything undeclared when the learner declares a list. */
    public static List<String> violations(LearnerDescriptor learner, Collection<String> used) {
        List<String> out = new ArrayList<>();
        for (String u : used) {
            if (isProtected(u)) out.add(u + " (protected attribute)");
            else if (!learner.features().isEmpty() && learner.features().stream().noneMatch(f -> u.equals(f) || u.startsWith(f + "."))) out.add(u + " (not declared by " + learner.key() + ")");
        }
        return out;
    }

    /** Throws when a learner would read a feature it may not use. */
    public static void requireAllowed(LearnerDescriptor learner, Collection<String> used) {
        List<String> v = violations(learner, used);
        if (!v.isEmpty()) throw new IllegalStateException("GV-07: learner " + learner.key() + " may not use " + v);
    }

    /** The feature map with every disallowed entry removed. */
    public static Map<String, Object> filter(LearnerDescriptor learner, Map<String, Object> features) {
        Map<String, Object> out = new LinkedHashMap<>();
        features.forEach((k, v) -> { if (violations(learner, List.of(k)).isEmpty()) out.put(k, v); });
        return out;
    }

    /**
     * Removes keys a contributor added to {@code merged} (anywhere in nested maps and lists of maps) that carry a
     * protected name and were not in {@code original}. Returns the dotted paths removed.
     */
    @SuppressWarnings("unchecked")
    public static List<String> stripAddedProtected(Map<String, Object> original, Map<String, Object> merged) {
        List<String> removed = new ArrayList<>();
        strip(original, merged, "", removed);
        return removed;
    }

    @SuppressWarnings("unchecked")
    private static void strip(Object original, Object merged, String path, List<String> removed) {
        if (merged instanceof Map<?, ?> mm) {
            Map<String, Object> m = (Map<String, Object>) mm;
            Map<String, Object> o = original instanceof Map<?, ?> om ? (Map<String, Object>) om : Map.of();
            for (String k : new ArrayList<>(m.keySet())) {
                String p = path.isEmpty() ? k : path + "." + k;
                if (!o.containsKey(k) && isProtected(k)) { m.remove(k); removed.add(p); continue; }
                strip(o.get(k), m.get(k), p, removed);
            }
        } else if (merged instanceof List<?> ml) {
            List<?> ol = original instanceof List<?> l ? l : List.of();
            for (int i = 0; i < ml.size(); i++) strip(i < ol.size() ? ol.get(i) : null, ml.get(i), path + "[" + i + "]", removed);
        }
    }
}
