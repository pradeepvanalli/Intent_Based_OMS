package ai.intentoms.workflow.api;

import ai.intentoms.platform.jdbc.Page;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import ai.intentoms.workflow.task.HumanTask;
import ai.intentoms.workflow.task.HumanTaskService;
import ai.intentoms.workflow.task.TaskQuery;
import ai.intentoms.workflow.task.TaskRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/** Human task inbox (FR-WF-04): {@code task.read} for list/get, {@code task.work} for create/claim/complete/cancel. */
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {
    private final HumanTaskService tasks;

    private final ai.intentoms.workflow.task.TaskQueues queues;
    private final ai.intentoms.platform.security.AuthorizationService authz;

    public TaskController(HumanTaskService tasks, ai.intentoms.workflow.task.TaskQueues queues, ai.intentoms.platform.security.AuthorizationService authz) {
        this.tasks = tasks;
        this.queues = queues;
        this.authz = authz;
    }

    public record CreateRequest(String type, String queue, String subjectType, String subjectId, String title, Map<String, Object> payload,
                                Integer slaMinutes, Set<String> requiredSkills, Integer priority, String workflowId) {}
    public record ClaimRequest(String assignee) {}
    public record CompleteRequest(String outcome, String reason, Map<String, Object> details) {}
    public record ReasonRequest(String reason) {}

    @GetMapping
    @RequiresPermission(Permissions.TASK_READ)
    public Page<HumanTask> list(@RequestParam(required = false) String queue, @RequestParam(required = false) String status,
                                @RequestParam(required = false) String assignee, @RequestParam(required = false) String subjectType,
                                @RequestParam(required = false) String subjectId, @RequestParam(required = false) String workflowId,
                                @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "50") int limit,
                                @RequestParam(defaultValue = "false") boolean mine) {
        if (mine) {
            var p = authz.requirePrincipal();
            return tasks.listMine(queues.queuesFor(p.roles()), p.id(), status, offset, limit);
        }
        return tasks.list(new TaskQuery(queue, status, assignee, subjectType, subjectId, workflowId, offset, limit));
    }

    @GetMapping("/{id}")
    @RequiresPermission(Permissions.TASK_READ)
    public HumanTask get(@PathVariable String id) { return tasks.get(id); }

    @PostMapping
    @RequiresPermission(Permissions.TASK_WORK)
    @ResponseStatus(HttpStatus.CREATED)
    public HumanTask create(@RequestBody CreateRequest r) {
        return tasks.create(new TaskRequest(r.type(), r.queue(), r.subjectType(), r.subjectId(), r.title(), r.payload(),
            r.slaMinutes() == null ? null : Duration.ofMinutes(r.slaMinutes()), r.requiredSkills(), r.priority() == null ? 0 : r.priority(), r.workflowId()));
    }

    @PostMapping("/{id}/claim")
    @RequiresPermission(Permissions.TASK_WORK)
    public HumanTask claim(@PathVariable String id, @RequestBody(required = false) ClaimRequest body) {
        return tasks.claim(id, body == null ? null : body.assignee());
    }

    @PostMapping("/{id}/complete")
    @RequiresPermission(Permissions.TASK_WORK)
    public HumanTask complete(@PathVariable String id, @RequestBody CompleteRequest body) {
        return tasks.complete(id, body.outcome(), body.reason(), body.details());
    }

    @PostMapping("/{id}/cancel")
    @RequiresPermission(Permissions.TASK_WORK)
    public HumanTask cancel(@PathVariable String id, @RequestBody(required = false) ReasonRequest body) {
        return tasks.cancel(id, body == null ? null : body.reason());
    }
}
