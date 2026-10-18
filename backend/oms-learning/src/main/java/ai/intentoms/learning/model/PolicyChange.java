package ai.intentoms.learning.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/**
 * The author LLM's only output: a diff against a versioned artifact (requirements doc §6.2). Never applied
 * directly — every variant is re-validated against the live artifact before it can become a {@link Proposal}, and
 * publishing still goes through the existing decision lint/golden-set/approval gate (EX-07).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = PolicyChange.ThresholdChange.class, name = "ThresholdChange"),
    @JsonSubTypes.Type(value = PolicyChange.WeightChange.class, name = "WeightChange"),
    @JsonSubTypes.Type(value = PolicyChange.RuleAdd.class, name = "RuleAdd"),
    @JsonSubTypes.Type(value = PolicyChange.RuleRetire.class, name = "RuleRetire"),
    @JsonSubTypes.Type(value = PolicyChange.ParameterChange.class, name = "ParameterChange"),
    @JsonSubTypes.Type(value = PolicyChange.AllowListChange.class, name = "AllowListChange")
})
@JsonIgnoreProperties(ignoreUnknown = true)
public sealed interface PolicyChange {
    /** Move one confidence-band threshold for a decision (DF-01). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ThresholdChange(@NotBlank String decision, @NotBlank String band,
                            @DecimalMin("0") @DecimalMax("1") double from, @DecimalMin("0") @DecimalMax("1") double to) implements PolicyChange {}

    /** Move one weight in a named weight vector (e.g. sourcing-score weights, FS-04); the vector must still sum to 1. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record WeightChange(@NotBlank String target, @NotBlank String weightKey, double from, double to) implements PolicyChange {}

    /** Propose a new L0 CEL rule (FD-04, DF-04: override-to-rule promotion). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RuleAdd(@NotBlank String decision, @NotBlank String expression, @NotBlank String thenValue, String reason) implements PolicyChange {}

    /** Propose retiring an L0 rule that never fires or that L1 now outperforms on replay (DF-07). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RuleRetire(@NotBlank String decision, @NotBlank String ruleId, String reason) implements PolicyChange {}

    /** Move a bounded numeric parameter the fast loop doesn't already own outright (e.g. a guardrail cap). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ParameterChange(@NotBlank String parameter, Object from, Object to) implements PolicyChange {}

    /** Add or remove an entry from a named allow-list (e.g. autonomous action types) — never refund/cancel. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record AllowListChange(@NotBlank String listName, @NotBlank String entry, boolean add) implements PolicyChange {}

    default Map<String, Object> describe() { return Map.of("type", getClass().getSimpleName()); }
}
