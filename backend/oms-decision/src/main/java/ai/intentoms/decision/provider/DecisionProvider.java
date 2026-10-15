package ai.intentoms.decision.provider;

/**
 * A System One or LLM back end that answers typed questions (FR-DEC-08, section 9 rule 9 "never single-vendor").
 * Implementations must return only enumerated values (GR-04): a choice key from the criteria, a score level within the
 * rubric, or a probability for a noul. Names: "jev", "llm.anthropic", "llm.openai", "heuristic".
 */
public interface DecisionProvider {
    String name();

    /** False when not configured, so the runtime skips it in the fallback chain. */
    boolean available();

    /** Ask every question in one call within {@code req.timeoutMs()}; throws {@link ProviderException} on failure. */
    ProviderResponse ask(ProviderRequest req);
}
