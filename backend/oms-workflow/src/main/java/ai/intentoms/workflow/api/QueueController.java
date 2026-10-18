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
    private final ai.intentoms.workflow.task.HumanTaskService tasks;

    public QueueController(TaskQueueService queues, ai.intentoms.workflow.task.HumanTaskService tasks) {
        this.queues = queues;
        this.tasks = tasks;
    }

    /**
     * A queue with its live load: {@code slaDefault} as an ISO-8601 duration, open / claimed / overdue counts. Queues
     * that have open work but no configuration row are listed too (with the default SLA), so no task is invisible.
     */
    public record QueueView(String name, int defaultSlaMinutes, String slaDefault, String escalationQueue, Set<String> skills, String description,
                            long openCount, long claimedCount, long overdueCount) {}

    public record QueueRequest(Integer defaultSlaMinutes, String escalationQueue, Set<String> skills, String description) {}

    @GetMapping
    @RequiresPermission(Permissions.TASK_READ)
    public List<QueueView> list() {
        java.util.Map<String, long[]> counts = tasks.queueCounts();
        java.util.Map<String, TaskQueue> byName = new java.util.TreeMap<>();
        queues.list().forEach(q -> byName.put(q.name(), q));
        counts.keySet().forEach(n -> byName.computeIfAbsent(n, k -> new TaskQueue(k, 0, null, Set.of(), null)));
        return byName.values().stream().map(q -> {
            long[] c = counts.getOrDefault(q.name(), new long[3]);
            return new QueueView(q.name(), q.defaultSlaMinutes(), java.time.Duration.ofMinutes(q.defaultSlaMinutes()).toString(), q.escalationQueue(),
                q.skills(), q.description(), c[0], c[1], c[2]);
        }).toList();
    }

    @GetMapping("/{name}")
    @RequiresPermission(Permissions.TASK_READ)
    public TaskQueue get(@PathVariable String name) { return queues.get(name); }

    @PutMapping("/{name}")
    @RequiresPermission(Permissions.CONFIG_ADMIN)
    public TaskQueue upsert(@PathVariable String name, @RequestBody QueueRequest r) {
        return queues.upsert(new TaskQueue(name, r.defaultSlaMinutes() == null ? 0 : r.defaultSlaMinutes(), r.escalationQueue(), r.skills(), r.description()));
    }
}
