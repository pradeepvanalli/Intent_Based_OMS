package ai.intentoms.orchestration.autonomy;

import ai.intentoms.platform.config.TenantConfigStore;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Guardrail evaluation for the Agentic Control Tower (tenant-scoped runtime config, {@link AutonomyConfigKeys}).
 * {@code autonomy.enabled} ships off by default; every other check only matters once a tenant has turned it on.
 * {@code refund} and {@code cancel} are never autonomous regardless of configuration (hard rule, checked here and in
 * {@link #load()} which strips them from the effective allow-list even if a tenant mistakenly configured them).
 */
@Service
public class GuardrailService {
    private final TenantConfigStore config;
    private final AutonomousActionRepository actions;

    public GuardrailService(TenantConfigStore config, AutonomousActionRepository actions) {
        this.config = config;
        this.actions = actions;
    }

    /** The tenant's effective guardrail configuration, with the hard "never autonomous" rule always enforced. */
    public GuardrailConfig load() {
        boolean enabled = config.getBoolean(AutonomyConfigKeys.ENABLED, AutonomyConfigKeys.DEFAULT_ENABLED);
        boolean kill = config.getBoolean(AutonomyConfigKeys.KILL_SWITCH, AutonomyConfigKeys.DEFAULT_KILL_SWITCH);
        int maxPerDay = config.getInt(AutonomyConfigKeys.MAX_ACTIONS_PER_DAY, AutonomyConfigKeys.DEFAULT_MAX_ACTIONS_PER_DAY);
        BigDecimal maxUsd = config.getDecimal(AutonomyConfigKeys.MAX_USD_IMPACT_PER_ACTION, AutonomyConfigKeys.DEFAULT_MAX_USD_IMPACT_PER_ACTION);
        List<?> rawAllowed = config.get(AutonomyConfigKeys.ALLOWED_ACTIONS).<List<?>>map(v -> v instanceof List<?> l ? l : List.of(v)).orElse(AutonomyConfigKeys.DEFAULT_ALLOWED_ACTIONS);
        Set<String> allowed = new LinkedHashSet<>();
        for (Object o : rawAllowed) {
            String code = String.valueOf(o).toLowerCase(Locale.ROOT);
            if (AutonomousActionType.isKnownCode(code) && !AutonomousActionType.NEVER_AUTONOMOUS.contains(AutonomousActionType.fromCode(code))) allowed.add(code);
        }
        Double minConfidence = config.get(AutonomyConfigKeys.MIN_CONFIDENCE_OVERRIDE).map(v -> v instanceof Number n ? n.doubleValue() : Double.parseDouble(v.toString())).orElse(null);
        return new GuardrailConfig(enabled, kill, Math.max(0, maxPerDay), maxUsd == null ? AutonomyConfigKeys.DEFAULT_MAX_USD_IMPACT_PER_ACTION : maxUsd, Set.copyOf(allowed), minConfidence);
    }

    /**
     * Whether an autonomous execution of {@code type} at {@code confidence} with an estimated {@code usdImpact} may
     * proceed right now. Checked in the order a person would check it: kill switch, master switch, hard rule,
     * allow-list, confidence floor, daily cap, dollar cap.
     */
    public GuardrailDecision evaluate(AutonomousActionType type, double confidence, BigDecimal usdImpact) {
        GuardrailConfig cfg = load();
        if (cfg.killSwitch()) return GuardrailDecision.block("the autonomy kill switch is engaged");
        if (!cfg.enabled()) return GuardrailDecision.block("autonomy is not enabled for this tenant");
        if (AutonomousActionType.NEVER_AUTONOMOUS.contains(type)) return GuardrailDecision.block("refund and cancel are never autonomous, regardless of configuration");
        if (!cfg.isAllowed(type)) return GuardrailDecision.block("action type '" + type.code() + "' is not on the tenant's allow-list");
        if (cfg.minConfidenceOverride() != null && confidence < cfg.minConfidenceOverride()) {
            return GuardrailDecision.block(String.format(Locale.ROOT, "confidence %.2f is below the tenant's minimum override %.2f", confidence, cfg.minConfidenceOverride()));
        }
        int today = actions.countExecutedToday();
        if (today >= cfg.maxActionsPerDay()) {
            return GuardrailDecision.block("daily autonomous action cap reached (" + today + "/" + cfg.maxActionsPerDay() + ")");
        }
        if (usdImpact != null && cfg.maxUsdImpactPerAction() != null && usdImpact.compareTo(cfg.maxUsdImpactPerAction()) > 0) {
            return GuardrailDecision.block("estimated impact $" + usdImpact.setScale(2, RoundingMode.HALF_UP) + " exceeds the per-action cap $" + cfg.maxUsdImpactPerAction().setScale(2, RoundingMode.HALF_UP));
        }
        return GuardrailDecision.allow();
    }
}
