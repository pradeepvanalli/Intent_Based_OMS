package ai.intentoms.agent.registry;

import java.util.Set;

/** Platform roles granted by trust tier (FR-AGT-03): 0 read-only, 1–2 shopper, 3 internal ops. */
public final class RolesByTier {
    private RolesByTier() {}
    public static final String READONLY = "agent_readonly";
    public static final String SHOPPER = "agent_shopper";
    public static final String OPS = "agent_ops";

    public static Set<String> roles(int trustTier) {
        return switch (clamp(trustTier)) {
            case 0 -> Set.of(READONLY);
            case 1, 2 -> Set.of(SHOPPER);
            default -> Set.of(OPS);
        };
    }

    public static int clamp(int tier) { return Math.max(0, Math.min(3, tier)); }
}
