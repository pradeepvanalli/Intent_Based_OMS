package ai.intentoms.learning.hooks;

import ai.intentoms.decision.model.DecisionOptions;

import java.util.List;
import java.util.Map;

/**
 * Adds learned features to one decision's input (CI-05, FS-02, ...). Implementations must read facts through
 * {@link ai.intentoms.learning.governance.ParameterResolver} or check live use themselves, return the input unchanged
 * when nothing applies, and list every fact they used so the decision record shows it (XP-03).
 */
public interface FeatureContributor {
    boolean appliesTo(String decision);

    /** Mutate {@code input} (a private copy) and add one entry per fact used to {@code factsUsed}. */
    void contribute(String decision, Map<String, Object> input, DecisionOptions opts, List<Map<String, Object>> factsUsed);
}
