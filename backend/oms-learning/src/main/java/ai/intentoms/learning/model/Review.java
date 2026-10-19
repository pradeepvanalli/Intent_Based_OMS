package ai.intentoms.learning.model;

import java.util.List;

/**
 * The critic's only output (requirements doc §6.1): evidence for a human reviewer, never the pass criterion.
 *
 * @param source who reviewed: {@value #LLM} (the critic model) or {@value #RULES} (the rule-based critic used when no
 *               model key is configured); null on reviews stored before the field existed, which were all the model's
 */
public record Review(Verdict verdict, List<String> risks, String source) {
    public enum Verdict { LOOKS_SAFE, NEEDS_CHANGES, RISKY }
    public static final String LLM = "llm";
    public static final String RULES = "rules";

    public Review(Verdict verdict, List<String> risks) { this(verdict, risks, LLM); }
}
