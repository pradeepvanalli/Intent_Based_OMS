package ai.intentoms.workflow.task;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/** A unit of human work routed to a queue with an SLA (FR-WF-04). Status: OPEN, CLAIMED, COMPLETED, CANCELLED, EXPIRED. */
public record HumanTask(String id, String type, String queue, String subjectType, String subjectId, String title, Map<String, Object> payload,
                        String status, String assignee, Instant slaDueAt, Instant createdAt, String outcome, String outcomeReason,
                        String completedBy, Instant completedAt, String workflowId, Set<String> requiredSkills, int priority) {
    public boolean isOpen() { return TaskStatus.OPEN.equals(status) || TaskStatus.CLAIMED.equals(status); }
}
