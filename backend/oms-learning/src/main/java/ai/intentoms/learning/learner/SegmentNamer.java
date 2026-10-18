package ai.intentoms.learning.learner;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Names discovered customer segments (CI-02). The analyst LLM provides names when a copilot key is configured (an
 * implementation lives in oms-copilot); without one, {@link #template} names each segment from what sets it apart.
 */
public interface SegmentNamer {
    /** What a segment looks like: its size and the mean of each behavioural feature (raw units, not standardised). */
    record Profile(int index, int size, Map<String, Double> means) {}

    record Name(String label, String description) {}

    /** Names in the same order as {@code profiles}, or empty to fall back to the template. */
    Optional<List<Name>> name(List<Profile> profiles, Map<String, Double> overallMeans);

    /** Feature keys, in the order the discovery builds its vectors. */
    List<String> FEATURES = List.of("split_tolerance", "speed_vs_cost", "pickup_share", "orders");

    /** Deterministic names from the two features that most set each segment apart from everyone else. */
    static List<Name> template(List<Profile> profiles, Map<String, Double> overall, Map<String, Double> spread) {
        List<Name> out = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        for (Profile p : profiles) {
            List<Map.Entry<String, Double>> z = new ArrayList<>();
            for (String f : FEATURES) {
                double s = Math.max(1e-6, spread.getOrDefault(f, 1.0));
                z.add(Map.entry(f, (p.means().getOrDefault(f, 0.0) - overall.getOrDefault(f, 0.0)) / s));
            }
            z.sort((a, b) -> Double.compare(Math.abs(b.getValue()), Math.abs(a.getValue())));
            String first = phrase(z.get(0).getKey(), z.get(0).getValue());
            String second = Math.abs(z.get(1).getValue()) >= 0.3 ? phrase(z.get(1).getKey(), z.get(1).getValue()) : null;
            String label = second == null ? capitalise(first) + " customers" : capitalise(first) + ", " + second + " customers";
            String base = label;
            int n = 2;
            while (!used.add(label)) label = base + " (" + n++ + ")";
            out.add(new Name(label, describe(p)));
        }
        return out;
    }

    static String phrase(String feature, double z) {
        boolean high = z >= 0;
        return switch (feature) {
            case "split_tolerance" -> high ? "split-tolerant" : "one-delivery";
            case "speed_vs_cost" -> high ? "speed-first" : "cost-conscious";
            case "pickup_share" -> high ? "store-pickup" : "home-delivery";
            default -> high ? "frequent" : "occasional";
        };
    }

    static String describe(Profile p) {
        Map<String, Double> m = p.means();
        return String.format(Locale.ROOT, "%d customers. Split tolerance %.2f, speed vs cost %+.2f, pickup %.0f%% of orders, %.1f orders each.",
            p.size(), m.getOrDefault("split_tolerance", 0.5), m.getOrDefault("speed_vs_cost", 0.0), 100 * m.getOrDefault("pickup_share", 0.0), m.getOrDefault("orders", 0.0));
    }

    private static String capitalise(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }
}
