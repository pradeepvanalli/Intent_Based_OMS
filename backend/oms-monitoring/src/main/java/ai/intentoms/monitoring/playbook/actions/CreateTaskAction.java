package ai.intentoms.monitoring.playbook.actions;

import ai.intentoms.monitoring.exception.ExceptionRecord;
import ai.intentoms.monitoring.exception.ExceptionRepository;
import ai.intentoms.monitoring.exception.ExceptionTaskFactory;
import ai.intentoms.monitoring.playbook.PlaybookAction;
import ai.intentoms.monitoring.playbook.PlaybookContext;
import ai.intentoms.monitoring.playbook.PlaybookStepResult;
import ai.intentoms.workflow.task.HumanTask;
import ai.intentoms.workflow.task.HumanTaskService;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Built-in {@code create_task} step (FR-MON-04): routes the exception to a human queue ({@code queue} parameter, default the
 * taxonomy queue). Idempotent per exception: when a linked task is still open the step is skipped.
 */
@Component
public class CreateTaskAction implements PlaybookAction {
    private final HumanTaskService tasks;
    private final ExceptionRepository exceptions;
    private final ExceptionTaskFactory factory;

    public CreateTaskAction(HumanTaskService tasks, ExceptionRepository exceptions, ExceptionTaskFactory factory) {
        this.tasks = tasks;
        this.exceptions = exceptions;
        this.factory = factory;
    }

    @Override public String action() { return "create_task"; }

    @Override
    public PlaybookStepResult run(PlaybookContext ctx, Map<String, Object> step) {
        ExceptionRecord e = exceptions.get(ctx.exception().id());
        if (e.taskId() != null) {
            try {
                HumanTask existing = tasks.get(e.taskId());
                if (existing.isOpen()) return PlaybookStepResult.skipped("task " + e.taskId() + " already open in " + existing.queue());
            } catch (RuntimeException ignored) { /* task gone: create a new one */ }
        }
        String queue = step.get("queue") == null ? e.queue() : String.valueOf(step.get("queue"));
        HumanTask task = tasks.create(factory.request(e, queue));
        exceptions.update(e.withTask(task.id()));
        return PlaybookStepResult.done("task " + task.id() + " created in " + queue, Map.of("taskId", task.id(), "queue", queue));
    }
}
