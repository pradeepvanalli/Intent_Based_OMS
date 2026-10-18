package ai.intentoms.copilot.learning;

import ai.intentoms.copilot.llm.JsonSchema;

import java.util.List;

/**
 * Strict output schemas for the Analyst, Author and Critic roles (SOO requirements doc §6.1-§6.3). Following the
 * same pattern {@code ChangeSetSchema} already uses for decision YAML: a change's concrete shape travels as a JSON
 * text blob ({@code argsJson}) rather than a schema-level {@code oneOf} over six variants, because strict
 * schema-constrained decoding generally can't express a discriminated union as reliably as a flat object — the
 * blob is deterministically parsed into the real sealed {@code PolicyChange} type afterward (never trusted as-is).
 */
public final class LearningSchemas {
    private LearningSchemas() {}

    private static final List<String> DIRECTIONS = List.of("INCREASE", "DECREASE");
    private static final List<String> CHANGE_TYPES = List.of("ThresholdChange", "WeightChange", "RuleAdd", "RuleRetire", "ParameterChange", "AllowListChange");
    private static final List<String> VERDICTS = List.of("LOOKS_SAFE", "NEEDS_CHANGES", "RISKY");

    public static final java.util.Map<String, Object> HYPOTHESIS = JsonSchema.object(
        "scopeKey", JsonSchema.string("Cohort dimension this hypothesis is scoped to, e.g. \"node\", \"lane\", \"segment\""),
        "scopeValue", JsonSchema.string("The cohort's value, e.g. \"DC-NV\""),
        "targetMetric", JsonSchema.string("Metric this hypothesis claims to move, e.g. otd, split, fraud, cx"),
        "expectedDirection", JsonSchema.enumOf("Which way the metric should move if the hypothesis is right", DIRECTIONS),
        "leverTarget", JsonSchema.string("What the lever would change, e.g. \"decision:sourcing_node_score\" or \"parameter:node_reliability\""),
        "leverDescription", JsonSchema.string("Plain description of the specific move, e.g. \"raise the act-band threshold from 0.80 to 0.85\""),
        "priorConfidence", JsonSchema.number("0..1: how confident the analyst is before this is tested"),
        "rationale", JsonSchema.string("One or two sentences, max 280 characters: why the digest evidence supports this"));

    public static final JsonSchema HYPOTHESES = JsonSchema.named("hypotheses", JsonSchema.object(
        "hypotheses", JsonSchema.array("Zero or more testable claims; empty when the digest shows nothing worth proposing", HYPOTHESIS)));

    private static final java.util.Map<String, Object> CHANGE = JsonSchema.object(
        "type", JsonSchema.enumOf("Which PolicyChange variant this is", CHANGE_TYPES),
        "argsJson", JsonSchema.string("JSON object text with exactly this variant's fields: ThresholdChange{decision,band,from,to}, "
            + "WeightChange{target,weightKey,from,to}, RuleAdd{decision,expression,thenValue,reason}, RuleRetire{decision,ruleId,reason}, "
            + "ParameterChange{parameter,from,to}, AllowListChange{listName,entry,add}"));

    public static final JsonSchema CHANGESET = JsonSchema.named("policy_changeset", JsonSchema.object(
        "targetType", JsonSchema.enumOf("Artifact kind this changeset targets", List.of("decision", "parameter")),
        "targetName", JsonSchema.string("The decision name or parameter key"),
        "tier", JsonSchema.enumOf("Risk tier of the target (copy from the hypothesis's decision/parameter, never invent)", List.of("T0", "T1", "T2", "T3")),
        "motivatedBy", JsonSchema.string("One sentence tying this changeset back to its hypothesis"),
        "changes", JsonSchema.array("One to three changes; small, reviewable moves, not a rewrite", CHANGE)));

    public static final JsonSchema REVIEW = JsonSchema.named("critic_review", JsonSchema.object(
        "verdict", JsonSchema.enumOf("The critic's read; evidence for the human reviewer, never a pass/fail gate", VERDICTS),
        "risks", JsonSchema.array("Specific, concrete risks a reviewer should check before approving; empty only for LOOKS_SAFE", JsonSchema.string(null))));
}
