package ai.intentoms.decision.runtime;

import ai.intentoms.decision.definition.DecisionDefinitionParser;
import ai.intentoms.decision.model.Answer;
import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.decision.model.QuestionDef;
import ai.intentoms.decision.provider.ProviderRequest;
import ai.intentoms.decision.provider.ProviderResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TopKStageTest {
    private final DecisionDefinitionParser parser = new DecisionDefinitionParser();

    @Test
    void chunksScoreCallsAt200AndChoosesOverTopK() {
        String yaml = """
            decision: pick_node
            version: 1
            tier: T1
            provider: heuristic
            questions:
              node:
                type: choice
                instructions: Which node should ship this order?
                criteria:
                  placeholder: replaced by candidates at runtime
                  none_of_these: No candidate is acceptable
              on_time:
                type: score
                instructions: Can this node ship on time?
                criteria: [very unlikely to ship, unlikely to ship, even chance to ship, likely to ship, almost certain to ship]
            topK: { scoreQuestion: on_time, k: 5, candidatesField: candidates }
            """;
        DecisionDefinition def = parser.parse(yaml);
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (int i = 0; i < 600; i++) candidates.add(Map.of("id", "node_" + i, "backlog", i));
        Map<String, Object> input = Map.of("candidates", candidates);
        List<ProviderRequest> calls = new ArrayList<>();
        var outcome = TopKStage.run(def, input, input, req -> {
            calls.add(req);
            Map<String, Answer> answers = new LinkedHashMap<>();
            for (QuestionDef q : req.questions().values()) {
                if (q.isScore()) {
                    int idx = Integer.parseInt(q.key().substring(q.key().lastIndexOf("__") + 2));
                    answers.put(q.key(), Answer.score(idx % 5, Map.of(), 0.9, Map.of()));   // nodes with idx % 5 == 4 score highest
                } else {
                    String first = q.choiceCriteria().keySet().iterator().next();
                    answers.put(q.key(), Answer.choice(first, Map.of(first, 1.0), 0.95));
                }
            }
            return new ProviderResponse(answers, "fake", 10, 0, 0, 1, Map.of());
        });
        assertThat(outcome).isNotNull();
        assertThat(outcome.candidates()).isEqualTo(600);
        assertThat(calls).hasSize(4);                                   // 3 score chunks of 200 + 1 choice
        assertThat(calls.get(0).questions()).hasSize(200);
        assertThat(calls.get(3).questions()).hasSize(1);
        QuestionDef stage2 = calls.get(3).questions().get("node");
        assertThat(stage2.choiceCriteria()).hasSize(6);                 // top 5 + escape
        assertThat(stage2.choiceCriteria()).containsKey("none_of_these");
        assertThat(outcome.ranking()).hasSize(5);
        outcome.ranking().forEach(r -> assertThat((double) r.get("score")).isEqualTo(1.0));
        assertThat(outcome.mainKey()).isEqualTo("node");
        assertThat(String.valueOf(outcome.answers().get("node").value())).startsWith("node_");
    }

    @Test
    void withoutChoiceQuestionTheRankingIsTheAnswer() {
        String yaml = """
            decision: rank
            version: 1
            tier: T1
            questions:
              on_time: { type: score, instructions: "on time", criteria: [low chance to ship, high chance to ship] }
              cost: { type: score, instructions: "cheap", criteria: [expensive to ship, cheap to ship] }
            composite: { weights: { on_time: 0.7, cost: 0.3 }, outputKey: node_score }
            topK: { scoreQuestion: on_time, k: 3, candidatesField: candidates }
            """;
        DecisionDefinition def = parser.parse(yaml);
        List<Map<String, Object>> candidates = List.of(Map.of("id", "a"), Map.of("id", "b"), Map.of("id", "c"), Map.of("id", "d"));
        Map<String, Object> input = Map.of("candidates", candidates);
        var outcome = TopKStage.run(def, input, input, req -> {
            Map<String, Answer> answers = new LinkedHashMap<>();
            req.questions().keySet().forEach(k -> answers.put(k, Answer.score(k.endsWith("__2") ? 1 : 0, Map.of(), 0.8, Map.of())));
            return new ProviderResponse(answers, "fake", 10, 0, 0, 1, Map.of());
        });
        assertThat(outcome.mainKey()).isEqualTo(TopKStage.RANKING_KEY);
        assertThat(outcome.answers().get(TopKStage.RANKING_KEY).value()).isEqualTo("c");
        assertThat(outcome.ranking()).hasSize(3);
        assertThat(outcome.calls()).isEqualTo(1);
    }

    @Test
    void returnsNullWithoutCandidates() {
        DecisionDefinition def = parser.parse("decision: r\nversion: 1\ntier: T1\nquestions:\n  s: { type: score, instructions: q, criteria: [low chance here, high chance here] }\ntopK: { scoreQuestion: s, k: 3, candidatesField: candidates }\n");
        assertThat(TopKStage.run(def, Map.of(), Map.of("other", 1), r -> null)).isNull();
    }
}
