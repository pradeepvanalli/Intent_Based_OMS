package ai.intentoms.agent.mcp;

import ai.intentoms.agent.AgentOrderService;
import ai.intentoms.agent.registry.AgentRegistry;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
import ai.intentoms.workflow.task.HumanTask;
import ai.intentoms.workflow.task.HumanTaskService;
import ai.intentoms.workflow.task.TaskRequest;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Human confirmation for tools listed in an agent's {@code confirmActions} (FR-AGT-04): the requested action is parked
 * as an {@code agent_confirmation} task in {@code risk_review} carrying the full call so a person can apply it, and the
 * agent receives {@code {status: "pending_confirmation", taskId}} instead of the effect. No agent approves its own
 * proposal (GR-14): the task is completed by a person with {@code task.work}.
 */
@Component
public class ConfirmationTasks {
    private final HumanTaskService tasks;
    private final EventStore events;

    public ConfirmationTasks(HumanTaskService tasks, EventStore events) {
        this.tasks = tasks;
        this.events = events;
    }

    public Map<String, Object> park(ToolContext ctx, String tool, String orderId, Map<String, Object> args, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", tool);
        payload.put("agentId", ctx.callerId());
        payload.put("orderId", orderId);
        payload.put("arguments", args);
        if (extra != null) payload.putAll(extra);
        HumanTask t = tasks.create(new TaskRequest(AgentOrderService.TASK_CONFIRMATION, AgentOrderService.QUEUE_RISK_REVIEW, orderId == null ? "agent" : "order", orderId == null ? ctx.callerId() : orderId,
            "Confirm " + tool + " requested by agent " + ctx.callerId() + (orderId == null ? "" : " on order " + orderId), payload, Duration.ofHours(4), Set.of(), 5, null));
        events.append(AgentRegistry.AGGREGATE, ctx.callerId(), new NewEvent("agent.confirmation_requested",
            Map.of("agentId", ctx.callerId(), "tool", tool, "taskId", t.id(), "orderId", orderId == null ? "" : orderId), Actor.agent(ctx.callerId()), null, orderId, Map.of()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "pending_confirmation");
        out.put("taskId", t.id());
        out.put("queue", AgentOrderService.QUEUE_RISK_REVIEW);
        out.put("message", tool + " needs human confirmation for agent " + ctx.callerId() + "; a reviewer will apply or reject it");
        return out;
    }
}
