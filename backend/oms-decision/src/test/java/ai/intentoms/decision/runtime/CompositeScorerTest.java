package ai.intentoms.decision.runtime;

import ai.intentoms.decision.model.Answer;
import ai.intentoms.decision.model.CompositeDef;
import ai.intentoms.decision.model.QuestionDef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CompositeScorerTest {
    @Test
    void weightedAverageOfNormalisedScores() {
        QuestionDef a = new QuestionDef("on_time", "score", "q", Map.of(), List.of("0", "1", "2", "3", "4"), null, false, null, null);
        QuestionDef b = new QuestionDef("cost", "score", "q", Map.of(), List.of("0", "1", "2"), null, false, null, null);
        Map<String, Answer> answers = Map.of("on_time", Answer.score(4, Map.of(), 0.9, Map.of()), "cost", Answer.score(1, Map.of(), 0.5, Map.of()));
        Answer c = CompositeScorer.combine(new CompositeDef(Map.of("on_time", 0.7, "cost", 0.3), "node_score"), Map.of("on_time", a, "cost", b), answers);
        // on_time 4/4 = 1.0 * 0.7 + cost 1/2 = 0.5 * 0.3 = 0.85
        assertThat(c.asDouble()).isCloseTo(0.85, within(1e-6));
        assertThat(c.confidence()).isCloseTo(0.7 * 0.9 + 0.3 * 0.5, within(1e-6));
        assertThat(c.type()).isEqualTo("score");
        assertThat(c.probabilities()).containsKeys("on_time", "cost");
    }

    @Test
    void missingComponentIsIgnored() {
        QuestionDef a = new QuestionDef("a", "score", "q", Map.of(), List.of("0", "1"), null, false, null, null);
        Answer c = CompositeScorer.combine(new CompositeDef(Map.of("a", 1.0, "b", 1.0), "out"), Map.of("a", a), Map.of("a", Answer.score(1, Map.of(), 0.8, Map.of())));
        assertThat(c.asDouble()).isEqualTo(1.0);
    }
}
