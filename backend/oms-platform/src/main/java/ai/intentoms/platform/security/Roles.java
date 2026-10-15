package ai.intentoms.platform.security;

import java.util.Map;
import java.util.Set;

/** Built-in roles mapped to permissions (tenant may extend via tenant_config key "security.roles"). */
public final class Roles {
    private Roles() {}

    public static final Map<String, Set<String>> DEFAULT = Map.ofEntries(
        Map.entry("admin", Set.of("*")),
        Map.entry("strategy_lead", Set.of("order.read", "decision.*", "statemodel.*", "analytics.read", "simulation.run", "task.read", "task.work", "exception.read", "config.admin", "margin.approve", "order.bulk", "inventory.read")),
        Map.entry("ops_analyst", Set.of("order.read", "order.modify", "order.hold", "order.release", "order.bulk", "task.*", "exception.*", "inventory.read", "decision.read", "decision.override", "analytics.read")),
        Map.entry("cs_rep", Set.of("order.read", "order.modify", "order.cancel", "return.create", "refund.issue", "appeasement.issue", "task.read", "task.work", "decision.read")),
        Map.entry("store_associate", Set.of("order.read", "task.read", "task.work")),
        Map.entry("risk_analyst", Set.of("order.read", "return.decide", "decision.read", "decision.approve", "decision.override", "task.*", "exception.read", "agent.admin")),
        Map.entry("finance_controller", Set.of("order.read", "refund.approve", "margin.approve", "analytics.read", "decision.read", "decision.approve", "task.read", "task.work")),
        Map.entry("implementer", Set.of("statemodel.*", "decision.edit", "decision.read", "config.admin", "order.read", "simulation.run")),
        Map.entry("ai_governance", Set.of("decision.*", "analytics.read", "simulation.run", "order.read")),
        Map.entry("platform_engineer", Set.of("*")),
        Map.entry("agent_readonly", Set.of("order.read", "promise.read", "inventory.read")),
        Map.entry("agent_shopper", Set.of("order.read", "order.create", "order.modify", "order.cancel", "return.create", "promise.read", "inventory.read")),
        Map.entry("agent_ops", Set.of("order.read", "order.modify", "order.hold", "order.release", "task.read", "task.work", "exception.*", "decision.read"))
    );

    public static Set<String> permissionsFor(Set<String> roles) {
        Set<String> out = new java.util.HashSet<>();
        for (String r : roles) out.addAll(DEFAULT.getOrDefault(r, Set.of()));
        return out;
    }
}
