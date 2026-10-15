package ai.intentoms.monitoring.playbook;

import java.util.Map;

/**
 * SPI for playbook steps (FR-MON-04). Built-ins live in this module ({@code notify, create_task, hold, classify, log});
 * orchestration registers the order-changing ones ({@code resource, repromise, contact_customer, reship, refund, cancel,
 * reauthorize, retry_workflow}) as {@code @Component}s. A step whose action has no handler is recorded as
 * {@code skipped: no handler}; a handler that throws is recorded as {@code failed} and never breaks the run.
 */
public interface PlaybookAction {
    /** The {@code action} key this handler implements. */
    String action();

    /** Execute the step. {@code step} is the raw YAML step map (action, gates and parameters). */
    PlaybookStepResult run(PlaybookContext ctx, Map<String, Object> step);
}
