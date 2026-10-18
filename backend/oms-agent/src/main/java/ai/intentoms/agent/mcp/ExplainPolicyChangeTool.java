package ai.intentoms.agent.mcp;

import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.store.ProposalRepository;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code explain_policy_change} (requirements doc §9.4): a typed summary of one promoted or pending policy change
 * — what it was, why, and what evidence supported it. Read-only. */
@Component
public class ExplainPolicyChangeTool implements McpTool {
    private final ProposalRepository proposals;
    public ExplainPolicyChangeTool(ProposalRepository proposals) { this.proposals = proposals; }

    @Override public String name() { return "explain_policy_change"; }
    @Override public String description() { return "Explain one SOO policy change: the hypothesis behind it, what it changes, its evaluation report and its current status."; }
    @Override public boolean readOnly() { return true; }
    @Override public String orderIdOf(Map<String, Object> args) { return null; }

    @Override
    public Map<String, Object> inputSchema() {
        return Schemas.object(Schemas.props("proposalId", Schemas.string("The proposal id, e.g. prop_...")), McpTool.required("proposalId"));
    }

    @Override
    public Object call(ToolContext ctx, Map<String, Object> args) {
        Object id = args.get("proposalId");
        if (id == null || id.toString().isBlank()) throw new JsonRpc.RpcException(JsonRpc.INVALID_PARAMS, "proposalId is required");
        Proposal p = proposals.get(id.toString());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("proposalId", p.proposalId());
        out.put("status", p.status().name());
        out.put("target", p.changeset().target().toString());
        out.put("tier", p.tier().name());
        out.put("changes", p.changeset().changes().stream().map(c -> c.getClass().getSimpleName()).toList());
        out.put("motivatedBy", p.changeset().motivatedBy());
        out.put("criticReview", p.criticReview());
        out.put("evaluationReport", p.evaluationReport());
        out.put("createdBy", p.createdBy());
        out.put("promotedAt", p.promotedAt() == null ? null : p.promotedAt().toString());
        return out;
    }
}
