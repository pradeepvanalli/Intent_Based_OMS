package ai.intentoms.orchestration.autonomy;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Effective guardrail configuration for the current tenant (resolved from {@link AutonomyConfigKeys}). */
public record GuardrailConfig(
    boolean enabled,
    boolean killSwitch,
    int maxActionsPerDay,
    BigDecimal maxUsdImpactPerAction,
    Set<String> allowedActions,
    Double minConfidenceOverride
) {
    public boolean isAllowed(AutonomousActionType type) {
        return !AutonomousActionType.NEVER_AUTONOMOUS.contains(type) && allowedActions.contains(type.code());
    }

    /** A redactable snapshot stored on every {@link AutonomousAction} / escalation payload for audit (GR-13 spirit). */
    public Map<String, Object> snapshot(int actionsToday) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("killSwitch", killSwitch);
        m.put("maxActionsPerDay", maxActionsPerDay);
        m.put("actionsToday", actionsToday);
        m.put("maxUsdImpactPerAction", maxUsdImpactPerAction);
        m.put("allowedActions", allowedActions);
        m.put("minConfidenceOverride", minConfidenceOverride);
        return m;
    }
}
