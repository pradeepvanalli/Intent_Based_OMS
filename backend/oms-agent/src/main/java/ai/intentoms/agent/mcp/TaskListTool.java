package ai.intentoms.agent.mcp;

import ai.intentoms.workflow.task.HumanTask;
import ai.intentoms.workflow.task.HumanTaskService;
import ai.intentoms.workflow.task.TaskQuery;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code task_list}: open human tasks by queue (FR-AGT-01, FR-AGT-06 internal agents). */
@Component
public class TaskListTool implements McpTool {
    private final HumanTaskService tasks;

    public TaskListTool(HumanTaskService tasks) { this.tasks = tasks; }

    @Override public String name() { return "task_list"; }
    @Override public String description() { return "List human tasks (default: OPEN) in a queue such as risk_review, margin_floor_approvals, refund_approvals or returns_ops."; }
    @Override public boolean readOnly() { return true; }
    @Override public String orderIdOf(Map<String, Object> args) { return null; }

    @Override
    public Map<String, Object> inputSchema() {
        return Schemas.object(Schemas.props("queue", Schemas.string("Queue name"), "status", Schemas.string("OPEN | CLAIMED | COMPLETED | CANCELLED (default OPEN)"),
            "limit", Schemas.integer("Max rows", 1, 200)), List.of());
    }

    @Override
    public Object call(ToolContext ctx, Map<String, Object> args) {
        int limit = args.get("limit") instanceof Number n ? n.intValue() : 50;
        String status = args.get("status") == null ? "OPEN" : args.get("status").toString();
        var page = tasks.list(new TaskQuery(args.get("queue") == null ? null : args.get("queue").toString(), status, null, null, null, null, 0, limit));
        List<Map<String, Object>> items = page.items().stream().map(TaskListTool::task).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", page.total());
        return out;
    }

    static Map<String, Object> task(HumanTask t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id());
        m.put("type", t.type());
        m.put("queue", t.queue());
        m.put("title", t.title());
        m.put("status", t.status());
        m.put("subjectType", t.subjectType());
        m.put("subjectId", t.subjectId());
        m.put("assignee", t.assignee());
        m.put("priority", t.priority());
        m.put("slaDueAt", t.slaDueAt());
        m.put("createdAt", t.createdAt());
        m.put("payload", t.payload());
        m.put("outcome", t.outcome());
        return m;
    }
}
