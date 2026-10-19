package ai.intentoms.learning.model;

import java.time.Instant;
import java.util.List;

/**
 * The critic's only output (requirements doc §6.1): evidence for a human reviewer, never the pass criterion.
 *
 * @param source     who reviewed: {@value #LLM} (the critic model) or {@value #RULES} (the rule-based critic used when no
 *                   model key is configured or the monthly budget is spent); null on reviews stored before the field
 *                   existed, which were all the model's
 * @param reviewedAt when the review was made (XP-02: it runs automatically after every evaluation); null on older reviews
 */
public record Review(Verdict verdict, List<String> risks, String source, Instant reviewedAt) {
    public enum Verdict { LOOKS_SAFE, NEEDS_CHANGES, RISKY }
    public static final String LLM = "llm";
    public static final String RULES = "rules";

    public Review(Verdict verdict, List<String> risks, String source) { this(verdict, risks, source, null); }

    public Review(Verdict verdict, List<String> risks) { this(verdict, risks, LLM); }
}
