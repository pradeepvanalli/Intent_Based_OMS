package ai.intentoms.learning.hooks;

import java.util.Map;

/** Exposes the hooks' package-private deep copy to tests in other packages. */
public final class LearningDecisionHooksTestAccess {
    private LearningDecisionHooksTestAccess() {}

    public static Map<String, Object> copy(Map<String, Object> in) { return LearningDecisionHooks.deepCopy(in); }
}
