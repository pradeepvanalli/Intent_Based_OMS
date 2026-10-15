package ai.intentoms.workflow.task;

/** Filter for {@link HumanTaskService#list}. Null fields are ignored; results are ordered by priority desc, SLA due asc. */
public record TaskQuery(String queue, String status, String assignee, String subjectType, String subjectId, String workflowId, int offset, int limit) {
    public TaskQuery {
        if (offset < 0) offset = 0;
        if (limit <= 0 || limit > 500) limit = 50;
    }
    public static TaskQuery open(String queue) { return new TaskQuery(queue, TaskStatus.OPEN, null, null, null, null, 0, 50); }
}
