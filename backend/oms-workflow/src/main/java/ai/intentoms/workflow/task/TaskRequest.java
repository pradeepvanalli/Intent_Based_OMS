package ai.intentoms.workflow.task;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * Request to create a human task (FR-WF-04).
 *
 * @param sla            time to resolution; null uses the queue's default SLA
 * @param requiredSkills skills a worker must have to claim it (informational for routing UIs; recorded on the task)
 * @param priority       higher first within a queue
 * @param workflowId     when set, completion signals the workflow with {@code task:<taskId>}
 */
public record TaskRequest(String type, String queue, String subjectType, String subjectId, String title, Map<String, Object> payload,
                          Duration sla, Set<String> requiredSkills, int priority, String workflowId) {
    public TaskRequest {
        payload = payload == null ? Map.of() : payload;
        requiredSkills = requiredSkills == null ? Set.of() : Set.copyOf(requiredSkills);
    }

    public static TaskRequest of(String type, String queue, String title) { return new TaskRequest(type, queue, null, null, title, Map.of(), null, Set.of(), 0, null); }

    public TaskRequest withSubject(String subjectType, String subjectId) { return new TaskRequest(type, queue, subjectType, subjectId, title, payload, sla, requiredSkills, priority, workflowId); }
    public TaskRequest withPayload(Map<String, Object> p) { return new TaskRequest(type, queue, subjectType, subjectId, title, p, sla, requiredSkills, priority, workflowId); }
    public TaskRequest withSla(Duration d) { return new TaskRequest(type, queue, subjectType, subjectId, title, payload, d, requiredSkills, priority, workflowId); }
    public TaskRequest withWorkflow(String wfId) { return new TaskRequest(type, queue, subjectType, subjectId, title, payload, sla, requiredSkills, priority, wfId); }
    public TaskRequest withPriority(int p) { return new TaskRequest(type, queue, subjectType, subjectId, title, payload, sla, requiredSkills, p, workflowId); }
    public TaskRequest withSkills(Set<String> s) { return new TaskRequest(type, queue, subjectType, subjectId, title, payload, sla, s, priority, workflowId); }
}
