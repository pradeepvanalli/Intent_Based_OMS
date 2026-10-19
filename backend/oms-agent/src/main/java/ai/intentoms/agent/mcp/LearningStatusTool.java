package ai.intentoms.agent.mcp;

import ai.intentoms.learning.governance.LearnerDescriptor;
import ai.intentoms.learning.governance.LearnerRegistry;
import ai.intentoms.learning.model.LearnerState;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.store.LearnerRepository;
import ai.intentoms.learning.store.ProposalRepository;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code learning_status} (requirements doc §9.4): what is learning right now and what changed — every learner with its
 * autonomy and whether it is switched off or paused (and why), the proposal queue, recent promotions and recent
 * rollbacks with their reasons. Read-only; no MCP tool can approve or promote a proposal.
 */
@Component
public class LearningStatusTool implements McpTool {
    private final LearnerRepository learners;
    private final ProposalRepository proposals;
    private final LearnerRegistry registry;

    public LearningStatusTool(LearnerRepository learners, ProposalRepository proposals, LearnerRegistry registry) {
        this.learners = learners;
        this.proposals = proposals;
        this.registry = registry;
    }

    @Override public String name() { return "learning_status"; }
    @Override public String description() { return "What Self-Optimizing Order Orchestration is learning right now: learners with their autonomy and any pause or switch-off with its reason, the proposal queue, recent promotions and recent rollbacks."; }
    @Override public boolean readOnly() { return true; }
    @Override public String orderIdOf(Map<String, Object> args) { return null; }
    @Override public Map<String, Object> inputSchema() { return Schemas.object(Map.of(), List.of()); }

    @Override
    public Object call(ToolContext ctx, Map<String, Object> args) {
        registry.ensureRegistered();
        List<LearnerState> all = learners.all();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("learners", all.stream().map(this::learner).toList());
        out.put("pausedLearners", all.stream().filter(s -> s.paused() || !s.enabled())
            .map(s -> Map.of("key", s.learnerKey(), "reason", s.paused() ? s.pausedReason() : "switched off")).toList());
        out.put("proposalsInDraft", proposals.countByStatus("DRAFT"));
        out.put("proposalsAwaitingApproval", proposals.countByStatus("EVALUATED"));
        out.put("proposalsPromotedRecently", proposals.byStatus("PROMOTED", 10).stream().map(Proposal::proposalId).toList());
        out.put("recentRollbacks", proposals.byStatus("ROLLED_BACK", 10).stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("proposalId", p.proposalId());
            m.put("target", p.changeset().target().toString());
            m.put("reason", p.rolledBackReason());
            m.put("at", p.rolledBackAt() == null ? null : p.rolledBackAt().toString());
            return m;
        }).toList());
        return out;
    }

    private Map<String, Object> learner(LearnerState s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", s.learnerKey());
        m.put("name", registry.descriptor(s.learnerKey()).map(LearnerDescriptor::displayName).orElse(s.learnerKey()));
        m.put("autonomyLevel", s.autonomyLevel().name());
        m.put("enabled", s.enabled());
        m.put("paused", s.paused());
        if (s.paused()) m.put("pausedReason", s.pausedReason());
        return m;
    }
}
