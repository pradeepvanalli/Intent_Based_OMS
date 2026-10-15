package ai.intentoms.workflow.api;

import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import ai.intentoms.workflow.task.TaskQueue;
import ai.intentoms.workflow.task.TaskQueueService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/** Task queue configuration (FR-WF-04): SLA, escalation target, skills. Reading needs {@code task.read}; changes {@code config.admin}. */
@RestController
@RequestMapping("/api/v1/queues")
public class QueueController {
    private final TaskQueueService queues;

    public QueueController(TaskQueueService queues) { this.queues = queues; }

    public record QueueRequest(Integer defaultSlaMinutes, String escalationQueue, Set<String> skills, String description) {}

    @GetMapping
    @RequiresPermission(Permissions.TASK_READ)
    public List<TaskQueue> list() { return queues.list(); }

    @GetMapping("/{name}")
    @RequiresPermission(Permissions.TASK_READ)
    public TaskQueue get(@PathVariable String name) { return queues.get(name); }

    @PutMapping("/{name}")
    @RequiresPermission(Permissions.CONFIG_ADMIN)
    public TaskQueue upsert(@PathVariable String name, @RequestBody QueueRequest r) {
        return queues.upsert(new TaskQueue(name, r.defaultSlaMinutes() == null ? 0 : r.defaultSlaMinutes(), r.escalationQueue(), r.skills(), r.description()));
    }
}
