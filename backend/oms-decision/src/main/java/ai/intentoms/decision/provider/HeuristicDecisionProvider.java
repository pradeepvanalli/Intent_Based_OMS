package ai.intentoms.decision.provider;

import ai.intentoms.decision.model.Answer;
import ai.intentoms.decision.model.QuestionDef;
import ai.intentoms.platform.json.Json;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic, always-available provider used in tests, demos without API keys and degraded mode. Choice: the option
 * whose key and description share the most tokens with the serialised state wins (ties: declaration order), else the
 * escape option; confidence 0.55-0.6 so it never reaches a T1+ act band on its own. Score: the level whose rubric shares
 * the most tokens with the state, else the middle level. Noul: keyword heuristics (gift, urgent, together, injection
 * phrases) else 0.5. No network, no randomness.
 */
@Component
public class HeuristicDecisionProvider implements DecisionProvider {
    public static final String NAME = "heuristic";
    private static final Pattern TOKEN = Pattern.compile("[^a-z0-9]+");
    private static final Set<String> STOP = Set.of("the", "a", "an", "is", "are", "to", "of", "in", "on", "for", "and", "or", "it", "this", "that", "with", "as", "be", "by", "at",
        "if", "not", "no", "yes", "than", "then", "its", "their", "they", "them", "one", "any", "all", "more", "most", "when", "which", "does", "do", "has", "have", "was", "were", "will",
        "would", "can", "could", "should", "from", "into", "about", "order", "customer", "item", "items", "true", "false", "null", "level", "buyer", "wants", "want", "other", "unclear",
        "information", "enough", "tell", "none", "these", "indicate", "indicates", "notes", "note", "very", "some", "each", "every", "may", "might", "likely", "given", "even", "so", "up");
    private static final List<String> INJECTION = List.of("ignore previous", "ignore all previous", "disregard", "system prompt", "you are now", "new instructions", "instructions:",
        "override", "as an ai", "developer mode", "jailbreak", "do not follow", "forget your");

    @Override public String name() { return NAME; }
    @Override public boolean available() { return true; }

    @Override
    public ProviderResponse ask(ProviderRequest req) {
        long start = System.nanoTime();
        String serialised = leafText(req.state()).toLowerCase(Locale.ROOT);
        Set<String> stateTokens = tokens(serialised);
        // Noul statements are matched against leaf VALUES only: a boolean flag's key (e.g. receipt: true) is structure,
        // and letting it match "the receipt is missing" would invert the meaning. Choice/Score keep the flag words.
        Set<String> noulTokens = tokens(leafText(req.state(), false).toLowerCase(Locale.ROOT));
        Map<String, Answer> out = new LinkedHashMap<>();
        for (QuestionDef q : req.questions().values()) {
            if (q.isChoice()) out.put(q.key(), choice(q, stateTokens));
            else if (q.isScore()) out.put(q.key(), score(q, stateTokens));
            else out.put(q.key(), Answer.noul(noul(q, serialised, noulTokens)));
        }
        int tokens = Math.max(1, Json.write(req.state()).length() / 4);
        return new ProviderResponse(out, "heuristic-1", tokens, 0, 0.0, (System.nanoTime() - start) / 1_000_000, Map.of("tokens", tokens));
    }

    Answer choice(QuestionDef q, Set<String> stateTokens) {
        String best = null;
        int bestScore = 0;
        int second = 0;
        Map<String, Integer> scores = new LinkedHashMap<>();
        String escape = q.escape();
        for (var e : q.choiceCriteria().entrySet()) {
            if (e.getKey().equals(escape)) { scores.put(e.getKey(), 0); continue; }
            int s = overlap(tokens(e.getKey().replace('_', ' ') + " " + e.getValue().toLowerCase(Locale.ROOT)), stateTokens);
            // The option key itself appearing in the state (e.g. intent_choices: ["ship_complete"]) is a strong signal.
            if (stateTokens.contains(e.getKey().toLowerCase(Locale.ROOT))) s += 3;
            scores.put(e.getKey(), s);
            if (s > bestScore) { second = bestScore; bestScore = s; best = e.getKey(); }
            else if (s > second) second = s;
        }
        if (best == null || bestScore == 0) {
            String pick = escape != null ? escape : q.choiceCriteria().keySet().iterator().next();
            return Answer.choice(pick, uniform(q, pick), 0.55);
        }
        int total = scores.values().stream().mapToInt(Integer::intValue).sum();
        Map<String, Double> probs = new LinkedHashMap<>();
        scores.forEach((k, v) -> probs.put(k, total == 0 ? 0.0 : round(v / (double) total)));
        double confidence = 0.55 + Math.min(0.05, 0.01 * (bestScore - second));
        return Answer.choice(best, probs, confidence);
    }

    Answer score(QuestionDef q, Set<String> stateTokens) {
        int n = q.levels();
        int best = -1, bestScore = 0;
        for (int i = 0; i < n; i++) {
            Set<String> levelTokens = tokens(q.scoreLevels().get(i).toLowerCase(Locale.ROOT));
            levelTokens.removeIf(t -> t.length() < 4);   // "not", "low", "no" are not evidence of a rubric level
            int s = overlap(levelTokens, stateTokens);
            if (s > bestScore) { bestScore = s; best = i; }
        }
        int level = best >= 0 ? best : n / 2;
        Map<String, Double> probs = new LinkedHashMap<>();
        Map<String, String> legend = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) { probs.put(String.valueOf(i), i == level ? 0.6 : round(0.4 / Math.max(1, n - 1))); legend.put(String.valueOf(i), q.scoreLevels().get(i)); }
        return Answer.score(level, probs, best >= 0 ? 0.6 : 0.55, legend);
    }

    double noul(QuestionDef q, String serialised, Set<String> stateTokens) {
        String statement = (q.effectiveInstructions() == null ? "" : q.effectiveInstructions()).toLowerCase(Locale.ROOT);
        boolean aboutInjection = statement.contains("instruction") || statement.contains("inject");
        if (aboutInjection) {
            for (String phrase : INJECTION) if (serialised.contains(phrase)) return 0.95;
            if (serialised.contains("instructions") || serialised.contains("ignore ")) return 0.8;
            return 0.05;
        }
        Set<String> st = tokens(statement);
        st.removeIf(t -> t.length() < 4);
        int hits = overlap(st, stateTokens);
        if (hits >= 2) return 0.92;
        if (hits == 1) return 0.8;
        return 0.5;
    }

    /**
     * Text of the leaf values only (keys are structure, not evidence); a boolean true leaf contributes its key so flags
     * such as {@code is_gift: true} are visible. Deterministic ordering follows the map iteration order.
     */
    static String leafText(Object node) { return leafText(node, true); }

    static String leafText(Object node, boolean includeBooleanKeys) {
        StringBuilder sb = new StringBuilder();
        appendLeaves(node, null, sb, includeBooleanKeys);
        return sb.toString();
    }

    private static void appendLeaves(Object node, String key, StringBuilder sb, boolean includeBooleanKeys) {
        if (node instanceof Map<?, ?> m) m.forEach((k, v) -> appendLeaves(v, String.valueOf(k), sb, includeBooleanKeys));
        else if (node instanceof List<?> l) l.forEach(v -> appendLeaves(v, key, sb, includeBooleanKeys));
        else if (node instanceof Boolean b) { if (b && key != null && includeBooleanKeys) sb.append(key.replace('_', ' ')).append(' '); }
        else if (node != null) sb.append(String.valueOf(node)).append(' ');
    }

    static Set<String> tokens(String text) {
        Set<String> out = new LinkedHashSet<>();
        for (String t : TOKEN.split(text.toLowerCase(Locale.ROOT))) if (t.length() > 1 && !STOP.contains(t)) out.add(t);
        return out;
    }

    static int overlap(Set<String> a, Set<String> b) { int n = 0; for (String t : a) if (b.contains(t)) n++; return n; }

    private static Map<String, Double> uniform(QuestionDef q, String pick) {
        Map<String, Double> probs = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>(q.choiceCriteria().keySet());
        double rest = keys.size() > 1 ? 0.4 / (keys.size() - 1) : 0.0;
        for (String k : keys) probs.put(k, k.equals(pick) ? 0.6 : round(rest));
        return probs;
    }

    private static double round(double d) { return Math.round(d * 1000) / 1000d; }
}
