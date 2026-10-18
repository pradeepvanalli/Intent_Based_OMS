package ai.intentoms.workflow.task;

import ai.intentoms.platform.jdbc.Page;

import java.util.Map;

/**
 * Human tasks (FR-WF-04): approvals, reviews and exception resolution routed to queues with SLA, skills and
 * escalation. Completing a task attached to a workflow signals it with {@code task:<taskId>} and payload
 * {@code {outcome, reason, details}} (FR-WF-05).
 */
public interface HumanTaskService {
    /** Create an OPEN task; an unknown queue is created lazily with the default SLA. Writes {@code task.created}. */
    HumanTask create(TaskRequest r);

    /** Claim an OPEN task (or re-claim one's own CLAIMED task). Null assignee means the current principal. Writes {@code task.claimed}. */
    HumanTask claim(String taskId, String assignee);

    /** Complete an OPEN or CLAIMED task; signals the workflow when attached. Writes {@code task.completed}. */
    HumanTask complete(String taskId, String outcome, String reason, Map<String, Object> details);

    /** Cancel an OPEN or CLAIMED task. Writes {@code task.cancelled}. */
    HumanTask cancel(String taskId, String reason);

    /** Merge keys into an OPEN or CLAIMED task's payload, e.g. a newer recommendation for the same question while the
     * task waits. Writes {@code task.refreshed}. */
    HumanTask refreshPayload(String taskId, Map<String, Object> patch);

    Page<HumanTask> list(TaskQuery q);

    /** Per queue: open, claimed and overdue (open or claimed past its SLA) task counts. */
    java.util.Map<String, long[]> queueCounts();

    /** Tasks in any of {@code queues} (null = every queue) or assigned to {@code assignee}, optionally by status. */
    Page<HumanTask> listMine(java.util.Set<String> queues, String assignee, String status, int offset, int limit);

    HumanTask get(String id);
}
