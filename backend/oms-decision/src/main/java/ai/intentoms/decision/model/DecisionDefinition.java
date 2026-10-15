package ai.intentoms.decision.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A parsed, versioned decision definition (FR-DEC-01; PRD Appendix B format extended with rules, composite, topK,
 * cache, mainQuestion, status and canaryPercent). Immutable; the YAML source is kept for diffing and re-publishing.
 *
 * @param dflt          L0 default value returned when AI is unavailable, killed, over budget or fails a check (GR-06, GR-07)
 * @param stateInclude  dotted paths copied from the input into the provider state (GR-09 minimal typed state)
 * @param bands         explicit bands, or null to use {@link Tier#defaultBands()}
 * @param mainQuestion  key of the question whose answer becomes the decision value; null = first choice question
 * @param status        draft | pending_approval | approved | shadow | canary | live | retired
 */
public record DecisionDefinition(String name, int version, String owner, Tier tier, String provider, String fallbackProvider, int timeoutMs, Object dflt,
                                 List<String> stateInclude, Map<String, QuestionDef> questions, Bands bands, List<String> preChecks, List<String> postChecks,
                                 List<RuleDef> rules, CompositeDef composite, TopKDef topK, EvaluationSpec evaluation, CacheDef cache, String mainQuestion,
                                 String status, int canaryPercent, String changeReason, String ticket, String yaml, Instant createdAt) {
    public static final int DEFAULT_TIMEOUT_MS = 800;

    public DecisionDefinition {
        stateInclude = stateInclude == null ? List.of() : List.copyOf(stateInclude);
        questions = questions == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(questions));
        preChecks = preChecks == null ? List.of() : List.copyOf(preChecks);
        postChecks = postChecks == null ? List.of() : List.copyOf(postChecks);
        rules = rules == null ? List.of() : List.copyOf(rules);
        if (timeoutMs <= 0) timeoutMs = DEFAULT_TIMEOUT_MS;
        if (status == null) status = "draft";
    }

    /** Effective bands: explicit bands, overridden to "never act" for T3, else the tier defaults. */
    public Bands effectiveBands() {
        if (tier == Tier.T3) return Bands.neverAct(bands == null ? 0.5 : bands.confirmGte());
        return bands != null ? bands : tier.defaultBands();
    }

    /** The question whose answer is the decision value: explicit {@code mainQuestion}, else first choice, else first question. */
    public Optional<QuestionDef> main() {
        if (mainQuestion != null && questions.containsKey(mainQuestion)) return Optional.of(questions.get(mainQuestion));
        return questions.values().stream().filter(QuestionDef::isChoice).findFirst().or(() -> questions.values().stream().findFirst());
    }

    /** Escape value for this decision: the main choice's escape option, else "unclassified" (T0 tier table). */
    public String escapeValue() {
        return main().map(QuestionDef::escape).filter(e -> e != null).orElse("unclassified");
    }

    public String key() { return name + "@" + version; }

    public DecisionDefinition withStatus(String status, int canaryPercent, String changeReason, String ticket, Instant createdAt) {
        return new DecisionDefinition(name, version, owner, tier, provider, fallbackProvider, timeoutMs, dflt, stateInclude, questions, bands, preChecks, postChecks,
            rules, composite, topK, evaluation, cache, mainQuestion, status, canaryPercent, changeReason, ticket, yaml, createdAt);
    }

    public DecisionDefinition withVersion(int version) {
        return new DecisionDefinition(name, version, owner, tier, provider, fallbackProvider, timeoutMs, dflt, stateInclude, questions, bands, preChecks, postChecks,
            rules, composite, topK, evaluation, cache, mainQuestion, status, canaryPercent, changeReason, ticket, yaml, createdAt);
    }
}
