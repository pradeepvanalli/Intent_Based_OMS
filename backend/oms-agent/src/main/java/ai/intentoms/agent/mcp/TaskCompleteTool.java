package ai.intentoms.agent.mcp;

import ai.intentoms.agent.AgentToolException;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.service.OrderService;
import ai.intentoms.platform.json.Json;
import ai.intentoms.workflow.task.HumanTask;
import ai.intentoms.workflow.task.HumanTaskService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * {@code task_complete}: completes a human task (FR-AGT-01, FR-AGT-06). Separation of duties (GR-14): an agent cannot
 * complete a task raised by its own call or about an order it placed.
 */
@Component
public class TaskCompleteTool implements McpTool {
    private final HumanTaskService tasks;
    private final OrderService orders;

    public TaskCompleteTool(HumanTaskService tasks, OrderService orders) {
        this.tasks = tasks;
        this.orders = orders;
    }

    @Override public String name() { return "task_complete"; }
    @Override public String description() { return "Complete a task with an outcome (approved, rejected, resolved, ...) and a reason. You cannot complete tasks about your own proposals or orders."; }
    @Override public boolean readOnly() { return false; }
    @Override public String orderIdOf(Map<String, Object> args) { return null; }

    @Override
    public Map<String, Object> inputSchema() {
        return Schemas.object(Schemas.props("taskId", Schemas.string("Task id"), "outcome", Schemas.string("Outcome keyword"), "reason", Schemas.string("Reason, recorded for audit"),
            "details", Schemas.anyObject("Structured details for the workflow")), List.of("taskId", "outcome", "reason"));
    }

    @Override
    public Object call(ToolContext ctx, Map<String, Object> args) {
        HumanTask t = tasks.get(args.get("taskId").toString());
        if (ctx.isAgent()) {
            Object proposer = t.payload() == null ? null : t.payload().get("agentId");
            if (ctx.callerId().equals(proposer)) throw new AgentToolException(AgentToolException.FORBIDDEN, "separation_of_duties", "Agent " + ctx.callerId() + " cannot approve its own proposal (GR-14)");
            if ("order".equals(t.subjectType()) && t.subjectId() != null) {
                try {
                    Order o = orders.get(t.subjectId());
                    if (o.provenance() != null && ctx.callerId().equals(o.provenance().agentId()))
                        throw new AgentToolException(AgentToolException.FORBIDDEN, "separation_of_duties", "Agent " + ctx.callerId() + " cannot decide on an order it placed (GR-14)");
                } catch (AgentToolException e) { throw e; } catch (RuntimeException ignored) { }
            }
        }
        Map<String, Object> details = args.get("details") == null ? Map.of() : Json.toMap(args.get("details"));
        HumanTask done = tasks.complete(t.id(), args.get("outcome").toString(), args.get("reason").toString(), details);
        return TaskListTool.task(done);
    }
}
