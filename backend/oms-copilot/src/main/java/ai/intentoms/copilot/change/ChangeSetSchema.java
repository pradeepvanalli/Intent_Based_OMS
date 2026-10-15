package ai.intentoms.copilot.change;

import ai.intentoms.copilot.llm.JsonSchema;

import java.util.List;
import java.util.Map;

/** Strict output schemas for the configuration copilot (GR-04). Artefacts and golden-case JSON travel as strings. */
public final class ChangeSetSchema {
    private ChangeSetSchema() {}

    private static final List<String> TIERS = List.of("T0", "T1", "T2", "T3");

    public static final Map<String, Object> TEST = JsonSchema.object(
        "caseId", JsonSchema.string("Short snake_case id unique within the change"),
        "input", JsonSchema.string("JSON object text: the decision input in its real shape"),
        "expected", JsonSchema.string("JSON object text: {\"value\": <expected decision value>}"),
        "notes", JsonSchema.string("What this case proves"));

    public static final Map<String, Object> CHANGE = JsonSchema.object(
        "id", JsonSchema.string("Short id such as c1, c2"),
        "kind", JsonSchema.enumOf("Artefact kind", ChangeKind.names()),
        "target", JsonSchema.string("Decision name, state model id, order type name, playbook name, 'sla', 'compliance' or 'tenant_config'"),
        "rationale", JsonSchema.string("Why this artefact and this shape implement the request"),
        "after", JsonSchema.string("The complete proposed artefact text (YAML, or JSON for tenant_config)"),
        "riskTier", JsonSchema.enumOf("Risk tier of the change", TIERS),
        "tests", JsonSchema.array("Golden cases (decision kinds only; empty otherwise)", TEST));

    public static final JsonSchema PROPOSAL = JsonSchema.named("change_set", JsonSchema.object(
        "summary", JsonSchema.string("One paragraph: what will change once published"),
        "assumptions", JsonSchema.array("Assumptions made where the request was silent", JsonSchema.string(null)),
        "questions", JsonSchema.array("Clarifying questions for the requester; empty when unambiguous", JsonSchema.string(null)),
        "changes", JsonSchema.array("The proposed artefacts", CHANGE)));

    public static final JsonSchema REPAIR = JsonSchema.named("change_repair", JsonSchema.object(
        "changes", JsonSchema.array("Corrected artefacts, one per failing change id", JsonSchema.object(
            "id", JsonSchema.string("The change id being repaired"),
            "after", JsonSchema.string("The complete corrected artefact text")))));

    public static final JsonSchema EXPLAIN = JsonSchema.named("change_explanation", JsonSchema.object(
        "summary", JsonSchema.string("Plain-language explanation of the diff"),
        "impacts", JsonSchema.array("Who/what is affected", JsonSchema.string(null)),
        "risks", JsonSchema.array("What to verify before publishing", JsonSchema.string(null))));
}
