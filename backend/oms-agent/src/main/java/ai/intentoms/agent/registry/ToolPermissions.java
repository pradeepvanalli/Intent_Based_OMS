package ai.intentoms.agent.registry;

import ai.intentoms.platform.security.Permissions;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Maps MCP tools (FR-AGT-01) to platform permissions so agents and people share one permission model (FR-AGT-06).
 * An agent's effective permission set is the mapping of its {@code allowedActions} intersected with its tier roles.
 */
public final class ToolPermissions {
    private ToolPermissions() {}

    public static final Map<String, String> BY_TOOL = Map.ofEntries(
        Map.entry("order_get", Permissions.ORDER_READ),
        Map.entry("order_search", Permissions.ORDER_READ),
        Map.entry("promise", Permissions.PROMISE_READ),
        Map.entry("order_create", Permissions.ORDER_CREATE),
        Map.entry("order_modify", Permissions.ORDER_MODIFY),
        Map.entry("order_cancel", Permissions.ORDER_CANCEL),
        Map.entry("return_create", Permissions.RETURN_CREATE),
        Map.entry("inventory_check", Permissions.INVENTORY_READ),
        Map.entry("decision_why", Permissions.DECISION_READ),
        Map.entry("task_list", Permissions.TASK_READ),
        Map.entry("task_complete", Permissions.TASK_WORK)
    );

    /** Permission required for a tool; unknown tools map to nothing (and are rejected by the executor). */
    public static String permissionFor(String tool) { return tool == null ? null : BY_TOOL.get(tool.toLowerCase()); }

    public static Set<String> permissionsFor(Collection<String> allowedActions) {
        Set<String> out = new LinkedHashSet<>();
        if (allowedActions == null) return out;
        for (String a : allowedActions) { String p = permissionFor(a); if (p != null) out.add(p); }
        return out;
    }
}
