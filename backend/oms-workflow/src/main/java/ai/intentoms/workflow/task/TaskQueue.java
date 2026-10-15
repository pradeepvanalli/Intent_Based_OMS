package ai.intentoms.workflow.task;

import java.util.Set;

/** A work queue with default SLA, escalation target and skills (FR-WF-04). */
public record TaskQueue(String name, int defaultSlaMinutes, String escalationQueue, Set<String> skills, String description) {
    public TaskQueue {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("queue name is required");
        if (defaultSlaMinutes <= 0) defaultSlaMinutes = TaskQueueService.DEFAULT_SLA_MINUTES;
        skills = skills == null ? Set.of() : Set.copyOf(skills);
    }
}
