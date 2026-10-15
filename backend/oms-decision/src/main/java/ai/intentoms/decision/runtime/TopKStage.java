package ai.intentoms.decision.runtime;

import ai.intentoms.decision.model.Answer;
import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.decision.model.QuestionDef;
import ai.intentoms.decision.model.TopKDef;
import ai.intentoms.decision.provider.ProviderRequest;
import ai.intentoms.decision.provider.ProviderResponse;
import ai.intentoms.platform.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Two-stage evaluation for large candidate sets (FR-DEC-05, section 9 rule 5). Stage 1 scores every candidate
 * independently (one Score question per candidate per weighted score question, at most 200 candidates per call), ranks
 * them by composite score and keeps the top K. Stage 2 runs the definition's Choice question over the top K (candidate
 * ids become the options, plus the escape option); without a Choice question the ranking itself is the answer under key
 * {@link #RANKING_KEY}.
 */
public final class TopKStage {
    public static final String RANKING_KEY = "ranking";
    public static final String ESCAPE_OPTION = "none_of_these";

    private TopKStage() {}

    /** Stage outcome: answers to merge, the ranking for the trace, and whether stage 2 produced a Choice answer. */
    public record Outcome(Map<String, Answer> answers, List<Map<String, Object>> ranking, String mainKey, int candidates, int calls) {}

    /** Returns null when the input has no candidate list at the configured field. */
    @SuppressWarnings("unchecked")
    public static Outcome run(DecisionDefinition def, Map<String, Object> state, Map<String, Object> input, Function<ProviderRequest, ProviderResponse> ask) {
        TopKDef topK = def.topK();
        Object raw = Json.path(input, topK.candidatesField());
        if (!(raw instanceof List<?> rawList) || rawList.isEmpty()) return null;
        Object projected = Json.path(state, topK.candidatesField());
        List<Object> candidates = projected instanceof List<?> pl && pl.size() == rawList.size() ? (List<Object>) pl : (List<Object>) rawList;
        Map<String, Object> context = new LinkedHashMap<>(state);
        context.remove(topK.candidatesField().split("\\.|\\[")[0]);

        List<String> scoreKeys = new ArrayList<>();
        if (def.composite() != null) def.composite().weights().keySet().stream().filter(k -> def.questions().containsKey(k) && def.questions().get(k).isScore()).forEach(scoreKeys::add);
        if (scoreKeys.isEmpty()) scoreKeys.add(topK.scoreQuestion());

        List<Map<String, Object>> ranking = new ArrayList<>();
        int calls = 0;
        for (int from = 0; from < candidates.size(); from += TopKDef.CHUNK) {
            int to = Math.min(candidates.size(), from + TopKDef.CHUNK);
            Map<String, QuestionDef> qs = new LinkedHashMap<>();
            List<Object> chunk = new ArrayList<>();
            for (int i = from; i < to; i++) {
                Object c = candidates.get(i);
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("index", i);
                cm.put("id", candidateId(c, i));
                cm.put("candidate", c);
                chunk.add(cm);
                for (String sk : scoreKeys) {
                    QuestionDef q = def.questions().get(sk);
                    qs.put(sk + "__" + i, new QuestionDef(sk + "__" + i, QuestionDef.SCORE, q.effectiveInstructions() + " Evaluate only candidate index " + i + " (id " + candidateId(c, i) + ").",
                        Map.of(), q.scoreLevels(), null, false, q.feeds(), null));
                }
            }
            Map<String, Object> chunkState = new LinkedHashMap<>(context);
            chunkState.put("candidates", chunk);
            ProviderResponse res = ask.apply(new ProviderRequest(chunkState, qs, def.timeoutMs(), null));
            calls++;
            for (int i = from; i < to; i++) {
                double sum = 0, wsum = 0, csum = 0;
                Map<String, Object> comps = new LinkedHashMap<>();
                for (String sk : scoreKeys) {
                    Answer a = res.answers().get(sk + "__" + i);
                    if (a == null) continue;
                    double w = def.composite() != null ? def.composite().weights().getOrDefault(sk, 1.0) : 1.0;
                    double norm = CompositeScorer.normalise(a.asDouble(), def.questions().get(sk).levels());
                    comps.put(sk, a.asDouble());
                    sum += w * norm; wsum += w; csum += w * a.confidence();
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", candidateId(candidates.get(i), i));
                row.put("index", i);
                row.put("score", wsum == 0 ? 0 : Math.round(sum / wsum * 10_000) / 10_000d);
                row.put("confidence", wsum == 0 ? 0 : Math.round(csum / wsum * 10_000) / 10_000d);
                row.put("components", comps);
                ranking.add(row);
            }
        }
        ranking.sort((a, b) -> Double.compare((double) b.get("score"), (double) a.get("score")));
        int k = Math.min(topK.k(), ranking.size());
        List<Map<String, Object>> top = new ArrayList<>(ranking.subList(0, k));
        for (int r = 0; r < top.size(); r++) top.get(r).put("rank", r + 1);

        Map<String, Answer> answers = new LinkedHashMap<>();
        QuestionDef choice = def.main().filter(QuestionDef::isChoice).orElse(null);
        String mainKey;
        if (choice != null) {
            Map<String, String> criteria = new LinkedHashMap<>();
            for (Map<String, Object> row : top) criteria.put(String.valueOf(row.get("id")), describe(candidates.get((int) row.get("index")), row));
            String escape = choice.escape() != null ? choice.escape() : ESCAPE_OPTION;
            criteria.put(escape, choice.choiceCriteria().getOrDefault(escape, "None of the candidates is acceptable"));
            QuestionDef stage2 = new QuestionDef(choice.key(), QuestionDef.CHOICE, choice.effectiveInstructions(), criteria, List.of(), null, false, choice.feeds(), escape);
            Map<String, Object> s2state = new LinkedHashMap<>(context);
            s2state.put("candidates", top);
            ProviderResponse res = ask.apply(new ProviderRequest(s2state, Map.of(choice.key(), stage2), def.timeoutMs(), null));
            calls++;
            answers.putAll(res.answers());
            mainKey = choice.key();
        } else {
            Map<String, Double> probs = new LinkedHashMap<>();
            double total = top.stream().mapToDouble(r -> (double) r.get("score")).sum();
            for (Map<String, Object> row : top) probs.put(String.valueOf(row.get("id")), total == 0 ? 1.0 / top.size() : Math.round((double) row.get("score") / total * 10_000) / 10_000d);
            Map<String, Object> best = top.get(0);
            answers.put(RANKING_KEY, Answer.choice(String.valueOf(best.get("id")), probs, (double) best.get("confidence")));
            mainKey = RANKING_KEY;
        }
        return new Outcome(answers, top, mainKey, candidates.size(), calls);
    }

    static String candidateId(Object c, int index) {
        if (c instanceof Map<?, ?> m) for (String k : List.of("id", "nodeId", "node_id", "candidateId", "candidate_id", "code", "name")) if (m.get(k) != null) return String.valueOf(m.get(k));
        return "candidate_" + index;
    }

    private static String describe(Object candidate, Map<String, Object> row) {
        String json = Json.write(candidate);
        if (json.length() > 240) json = json.substring(0, 240) + "...";
        return "score " + row.get("score") + " " + json;
    }
}
