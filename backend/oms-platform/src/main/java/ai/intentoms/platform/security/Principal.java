package ai.intentoms.platform.security;

import java.util.Set;

/**
 * Who is acting. People, agents, system jobs and decisions all become a Principal, so the permission model is
 * identical for UI, integrations and AI agents (design principle 5: agent-native, human-governed).
 *
 * @param type        USER | AGENT | SYSTEM | DECISION
 * @param id          stable identifier (user id, agent id, job name, decision record id)
 * @param tenantId    the tenant this principal belongs to
 * @param roles       role names (e.g. ops_analyst, cs_rep, finance_controller)
 * @param permissions flattened permission set (e.g. order.read, order.cancel)
 * @param trustTier   for agents: 0 = unknown/read-only, 1 = verified, 2 = trusted partner, 3 = internal
 */
public record Principal(Type type, String id, String tenantId, Set<String> roles, Set<String> permissions, int trustTier) {
    public enum Type { USER, AGENT, SYSTEM, DECISION }

    public static Principal system(String tenantId) {
        return new Principal(Type.SYSTEM, "system", tenantId, Set.of("system"), Set.of("*"), 3);
    }

    public static Principal decision(String tenantId, String decisionRecordId) {
        return new Principal(Type.DECISION, decisionRecordId, tenantId, Set.of("decision"), Set.of("*"), 3);
    }

    public boolean has(String permission) {
        if (permissions.contains("*") || permissions.contains(permission)) return true;
        int dot = permission.indexOf('.');
        return dot > 0 && permissions.contains(permission.substring(0, dot) + ".*");
    }

    public boolean isAgent() { return type == Type.AGENT; }

    public String actorRef() { return type.name().toLowerCase() + ":" + id; }
}
