package ai.intentoms.decision.model;

import java.util.Map;

/**
 * A typed provider answer. {@code value} is the option key (choice), the score as a double (score) or the probability
 * (noul). {@code confidence} is on one 0..1 scale: provider confidence for choice/score, {@code |2p-1|} for noul
 * (PRD section 9 rule 7).
 */
public record Answer(String type, Object value, Map<String, Double> probabilities, double confidence, Map<String, String> legend) {
    public Answer {
        probabilities = probabilities == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(probabilities));
        legend = legend == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(legend));
        confidence = Math.max(0.0, Math.min(1.0, confidence));
    }

    /** Noul answer from a probability; confidence = |2p-1|. */
    public static Answer noul(double p) {
        double pc = Math.max(0.0, Math.min(1.0, p));
        return new Answer(QuestionDef.NOUL, pc, Map.of("true", pc, "false", 1.0 - pc), Math.abs(2 * pc - 1), Map.of());
    }

    public static Answer choice(String option, Map<String, Double> probabilities, double confidence) {
        return new Answer(QuestionDef.CHOICE, option, probabilities, confidence, Map.of());
    }

    public static Answer score(double score, Map<String, Double> probabilities, double confidence, Map<String, String> legend) {
        return new Answer(QuestionDef.SCORE, score, probabilities, confidence, legend);
    }

    public double asDouble() { return value instanceof Number n ? n.doubleValue() : Double.NaN; }
    /** A Noul is "true" only strictly above the undetermined point (p = 0.5 means the model could not tell). */
    public boolean asBoolean() { return value instanceof Boolean b ? b : asDouble() > 0.5; }
}
