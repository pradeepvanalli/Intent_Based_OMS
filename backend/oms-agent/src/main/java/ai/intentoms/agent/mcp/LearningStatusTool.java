package ai.intentoms.agent.mcp;

import ai.intentoms.learning.model.LearnerState;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.store.LearnerRepository;
import ai.intentoms.learning.store.ProposalRepository;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code learning_status} (requirements doc §9.4): what is learning right now and what changed. Read-only; no
 * MCP tool can approve or promote a proposal. */
@Component
public class LearningStatusTool implements McpTool {
    private final LearnerRepository learners;
    private final ProposalRepository proposals;

    public LearningStatusTool(LearnerRepository learners, ProposalRepository proposals) {
        this.learners = learners;
        this.proposals = proposals;
    }

    @Override public String name() { return "learning_status"; }
    @Override public String description() { return "What Self-Optimizing Order Orchestration is learning right now: active learners, their autonomy level, and the proposal queue."; }
    @Override public boolean readOnly() { return true; }
    @Override public String orderIdOf(Map<String, Object> args) { return null; }
    @Override public Map<String, Object> inputSchema() { return Schemas.object(Map.of(), List.of()); }

    @Override
    public Object call(ToolContext ctx, Map<String, Object> args) {
        List<LearnerState> all = learners.all();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("learners", all.stream().map(s -> Map.of("key", s.learnerKey(), "autonomyLevel", s.autonomyLevel().name(), "enabled", s.enabled())).toList());
        out.put("proposalsInDraft", proposals.countByStatus("DRAFT"));
        out.put("proposalsAwaitingApproval", proposals.countByStatus("EVALUATED"));
        out.put("proposalsPromotedRecently", proposals.byStatus("PROMOTED", 10).stream().map(Proposal::proposalId).toList());
        return out;
    }
}
