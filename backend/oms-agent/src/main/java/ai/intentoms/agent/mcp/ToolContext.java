package ai.intentoms.agent.mcp;

import ai.intentoms.agent.registry.AgentIdentity;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.security.Principal;

import java.util.Optional;

/**
 * Who is calling a tool (FR-AGT-06): the authenticated principal (agent or person), the registry identity when the
 * caller is a registered agent (limits apply) and the ledger actor.
 */
public record ToolContext(Principal principal, Optional<AgentIdentity> agent, Actor actor) {
    public static ToolContext of(Principal p, AgentIdentity identity) {
        return new ToolContext(p, Optional.ofNullable(identity), Actor.parse(p.actorRef()));
    }

    public boolean isAgent() { return principal.isAgent(); }
    public String callerId() { return principal.id(); }
    public int trustTier() { return principal.trustTier(); }

    /** Tier-0 agents may only touch orders whose provenance names them (FR-AGT-03). */
    public boolean ownershipRestricted() { return isAgent() && trustTier() <= 0; }

    public boolean requiresConfirmation(String tool) { return agent.map(a -> a.limits().requiresConfirmation(tool)).orElse(false); }
}
