package ai.intentoms.agent.mcp;

import ai.intentoms.agent.AgentToolException;
import ai.intentoms.decision.model.CustomerSafeTraceView;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.runtime.DecisionService;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.service.OrderService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** {@code decision_why}: customer-safe explanation of a decision record (FR-AGT-01, FR-XAI-04); tier-0 ownership via the record's subject. */
@Component
public class DecisionWhyTool implements McpTool {
    private final DecisionService decisions;
    private final OrderService orders;

    public DecisionWhyTool(DecisionService decisions, OrderService orders) {
        this.decisions = decisions;
        this.orders = orders;
    }

    @Override public String name() { return "decision_why"; }
    @Override public String description() { return "Why a decision was made, in customer-safe form: value, band, action, layer, reason codes and the questions answered. Internal economics and risk data are withheld."; }
    @Override public boolean readOnly() { return true; }
    @Override public String orderIdOf(Map<String, Object> args) { return null; }

    @Override
    public Map<String, Object> inputSchema() {
        return Schemas.object(Schemas.props("recordId", Schemas.string("Decision record id (from an order view or evidence pack)")), List.of("recordId"));
    }

    @Override
    public Object call(ToolContext ctx, Map<String, Object> args) {
        String recordId = args.get("recordId").toString();
        if (ctx.ownershipRestricted()) {
            DecisionRecord rec = decisions.record(recordId);
            boolean owner = false;
            if ("order".equals(rec.subjectType())) {
                try { Order o = orders.get(rec.subjectId()); owner = o.provenance() != null && ctx.callerId().equals(o.provenance().agentId()); } catch (RuntimeException ignored) { }
            }
            if (!owner) throw new AgentToolException(AgentToolException.NOT_OWNER, "not_owner", "Decision " + recordId + " concerns a subject not owned by agent " + ctx.callerId());
        }
        return CustomerSafeTraceView.from(decisions.why(recordId));
    }
}
