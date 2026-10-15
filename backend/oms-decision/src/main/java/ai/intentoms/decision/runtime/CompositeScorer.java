package ai.intentoms.decision.runtime;

import ai.intentoms.decision.model.Answer;
import ai.intentoms.decision.model.CompositeDef;
import ai.intentoms.decision.model.QuestionDef;

import java.util.Map;

/**
 * Composite scoring (FR-DEC-04, section 9 rule 1): each Score answer is normalised to 0..1 by its rubric size
 * ({@code score / (levels - 1)}), combined as a weighted average, and the composite confidence is the weighted mean of
 * the component confidences. Changing a priority means editing a weight, not a prompt.
 */
public final class CompositeScorer {
    private CompositeScorer() {}

    public static Answer combine(CompositeDef composite, Map<String, QuestionDef> questions, Map<String, Answer> answers) {
        double sum = 0, wsum = 0, csum = 0;
        Map<String, Double> components = new java.util.LinkedHashMap<>();
        for (var e : composite.weights().entrySet()) {
            Answer a = answers.get(e.getKey());
            QuestionDef q = questions.get(e.getKey());
            if (a == null || q == null) continue;
            double w = e.getValue();
            double norm = normalise(a.asDouble(), q.levels());
            components.put(e.getKey(), round(norm));
            sum += w * norm;
            wsum += w;
            csum += w * a.confidence();
        }
        if (wsum == 0) return Answer.score(0, Map.of(), 0, Map.of());
        return Answer.score(round(sum / wsum), components, csum / wsum, Map.of("scale", "0..1 weighted normalised score"));
    }

    /** Normalises a score to 0..1 given the number of rubric levels. */
    public static double normalise(double score, int levels) {
        if (Double.isNaN(score)) return 0;
        if (levels <= 1) return Math.max(0, Math.min(1, score));
        return Math.max(0, Math.min(1, score / (levels - 1)));
    }

    private static double round(double d) { return Math.round(d * 10_000) / 10_000d; }
}
