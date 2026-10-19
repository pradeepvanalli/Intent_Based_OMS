package ai.intentoms.agent.reputation;

import ai.intentoms.agent.registry.AgentRegistry;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * FD-05: the manual trust-tier review. Lists each agent's {@link AgentReputation} next to its current tier and the
 * suggested one; changing a tier stays the admin's own call on the agent registry.
 */
@RestController
@RequestMapping("/api/v1/agents")
public class AgentReputationController {
    private final AgentReputationLearner learner;
    private final AgentRegistry agents;

    public AgentReputationController(AgentReputationLearner learner, AgentRegistry agents) {
        this.learner = learner;
        this.agents = agents;
    }

    @GetMapping("/reputation")
    @RequiresPermission(Permissions.AGENT_ADMIN)
    public List<AgentReputation> reputations() { return learner.reputations(); }

    @GetMapping("/{agentId}/reputation")
    @RequiresPermission(Permissions.AGENT_ADMIN)
    public AgentReputation reputation(@PathVariable String agentId) { return learner.reputation(agents.get(agentId)); }
}
