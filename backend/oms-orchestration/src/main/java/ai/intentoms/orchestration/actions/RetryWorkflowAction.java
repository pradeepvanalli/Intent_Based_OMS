package ai.intentoms.orchestration.actions;

import ai.intentoms.monitoring.playbook.PlaybookAction;
import ai.intentoms.monitoring.playbook.PlaybookContext;
import ai.intentoms.monitoring.playbook.PlaybookStepResult;
import ai.intentoms.order.model.Order;
import ai.intentoms.workflow.model.WorkflowStatus;
import ai.intentoms.workflow.model.WorkflowView;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Playbook {@code retry_workflow} (FR-MON-04/05, {@code diagnose_stuck}): wakes the order's workflow now. A FAILED instance
 * goes back to RUNNING on its current step; a waiting or backed-off instance runs immediately. Uses the exception's
 * {@code details.workflowId} when present (workflow.failed exceptions), else the order's workflow.
 */
@Component
public class RetryWorkflowAction implements PlaybookAction {
    private final ActionSupport support;

    public RetryWorkflowAction(ActionSupport support) { this.support = support; }

    @Override public String action() { return "retry_workflow"; }

    @Override
    public PlaybookStepResult run(PlaybookContext ctx, Map<String, Object> step) {
        Object explicit = ctx.exception().details().get("workflowId");
        Optional<WorkflowView> wf;
        if (explicit != null && !String.valueOf(explicit).isBlank()) {
            try { wf = Optional.of(support.engine().get(String.valueOf(explicit))); } catch (RuntimeException e) { wf = Optional.empty(); }
        } else {
            Optional<Order> order = support.order(ctx);
            wf = order.flatMap(o -> support.engine().find(support.workflowType(o), o.id()));
        }
        if (wf.isEmpty()) return PlaybookStepResult.skipped("no workflow instance to retry");
        WorkflowView w = wf.get();
        if (WorkflowStatus.COMPLETED.equals(w.status()) || WorkflowStatus.CANCELLED.equals(w.status()) || WorkflowStatus.COMPENSATED.equals(w.status()))
            return PlaybookStepResult.skipped("workflow " + w.id() + " is " + w.status());
        support.engine().retryNow(w.id());
        return PlaybookStepResult.done("workflow " + w.id() + " (" + w.status() + " at " + w.currentStep() + ") woken", Map.of("workflowId", w.id(), "previousStatus", w.status()));
    }
}
