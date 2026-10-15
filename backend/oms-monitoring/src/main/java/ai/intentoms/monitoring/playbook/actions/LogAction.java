package ai.intentoms.monitoring.playbook.actions;

import ai.intentoms.monitoring.playbook.PlaybookAction;
import ai.intentoms.monitoring.playbook.PlaybookContext;
import ai.intentoms.monitoring.playbook.PlaybookStepResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Built-in {@code log} step (FR-MON-04): writes the step's {@code message} to the application log with the exception context. */
@Component
public class LogAction implements PlaybookAction {
    private static final Logger log = LoggerFactory.getLogger(LogAction.class);

    @Override public String action() { return "log"; }

    @Override
    public PlaybookStepResult run(PlaybookContext ctx, Map<String, Object> step) {
        String message = step.get("message") == null ? ctx.exception().message() : String.valueOf(step.get("message"));
        log.info("Playbook log [{} {} order={}]: {}", ctx.exception().id(), ctx.exception().type(), ctx.exception().orderId(), message);
        return PlaybookStepResult.done("logged");
    }
}
