package ai.intentoms.workflow.task;

import ai.intentoms.platform.config.TenantConfigStore;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which task queues each role works, so "my tasks" (and the navigation badge) counts only work a person can act on.
 * Tenant config {@code tasks.queue_roles} ({@code {"<queue>": ["<role>", ...]}}) adds to or overrides the defaults
 * below. Admins and platform engineers see every queue.
 */
@Component
public class TaskQueues {
    static final Map<String, List<String>> DEFAULTS = new LinkedHashMap<>();
    static {
        DEFAULTS.put("ops_lead", List.of("ops_analyst", "strategy_lead"));
        DEFAULTS.put("ops_exceptions", List.of("ops_analyst"));
        DEFAULTS.put("decision_escalations", List.of("ops_analyst", "ai_governance", "strategy_lead"));
        DEFAULTS.put("platform_lead", List.of("ops_analyst"));
        DEFAULTS.put("platform_oncall", List.of("ops_analyst"));
        DEFAULTS.put("vendor_ops", List.of("ops_analyst"));
        DEFAULTS.put("store_ops", List.of("ops_analyst", "store_associate"));
        DEFAULTS.put("cs_exceptions", List.of("cs_rep"));
        DEFAULTS.put("cs_queue", List.of("cs_rep"));
        DEFAULTS.put("risk_review", List.of("risk_analyst"));
        DEFAULTS.put("fraud_review", List.of("risk_analyst"));
        DEFAULTS.put("finance_controller", List.of("finance_controller"));
        DEFAULTS.put("finance_exceptions", List.of("finance_controller"));
    }
    private static final Set<String> SEE_ALL = Set.of("admin", "platform_engineer");

    private final TenantConfigStore config;

    public TaskQueues(TenantConfigStore config) { this.config = config; }

    /** Queues these roles work; null means every queue. */
    @SuppressWarnings("unchecked")
    public Set<String> queuesFor(Set<String> roles) {
        if (roles.stream().anyMatch(SEE_ALL::contains)) return null;
        Map<String, List<String>> mapping = new LinkedHashMap<>(DEFAULTS);
        config.getMap("tasks.queue_roles").forEach((q, v) -> { if (v instanceof List<?> l) mapping.put(q, l.stream().map(String::valueOf).toList()); });
        Set<String> out = new TreeSet<>();
        mapping.forEach((queue, rs) -> { if (rs.stream().anyMatch(roles::contains)) out.add(queue); });
        return out;
    }
}
