package ai.intentoms.decision.model;

/**
 * Two-stage evaluation for large candidate sets (FR-DEC-05, section 9 rule 5): score each candidate found at
 * {@code candidatesField} with {@code scoreQuestion} (chunked), keep the top {@code k}, then choose among them.
 */
public record TopKDef(String scoreQuestion, int k, String candidatesField) {
    public static final int CHUNK = 200;
    public TopKDef {
        if (k <= 0) throw new IllegalArgumentException("topK.k must be positive");
        if (k > QuestionDef.MAX_CHOICE_OPTIONS) throw new IllegalArgumentException("topK.k must be <= 255 so the final Choice fits one call");
    }
}
