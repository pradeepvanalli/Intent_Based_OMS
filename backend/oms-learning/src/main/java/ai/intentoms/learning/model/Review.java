package ai.intentoms.learning.model;

import java.util.List;

/** The critic LLM's only output (requirements doc §6.1): evidence for a human reviewer, never the pass criterion. */
public record Review(Verdict verdict, List<String> risks) {
    public enum Verdict { LOOKS_SAFE, NEEDS_CHANGES, RISKY }
}
