package ai.intentoms.orchestration.workflow;

import ai.intentoms.monitoring.exception.ExceptionRecord;
import ai.intentoms.monitoring.exception.ExceptionRequest;
import ai.intentoms.monitoring.exception.ExceptionService;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.returns.model.RefundTiming;
import ai.intentoms.returns.model.ReturnRma;
import ai.intentoms.returns.model.ReturnStatus;
import ai.intentoms.returns.model.ReturnTasks;
import ai.intentoms.returns.service.ReturnService;
import ai.intentoms.workflow.model.Next;
import ai.intentoms.workflow.model.RetryPolicy;
import ai.intentoms.workflow.model.Signal;
import ai.intentoms.workflow.model.Step;
import ai.intentoms.workflow.model.WorkflowContext;
import ai.intentoms.workflow.model.WorkflowDefinition;
import ai.intentoms.workflow.task.HumanTask;
import ai.intentoms.workflow.task.HumanTaskService;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code return.standard} v1 (UC-08, FR-RET-01..06, FR-WF-04): started by {@link ReturnEventSubscriber} on
 * {@code return.requested} with the RMA id as business key. {@code decide} runs {@code ReturnService.decideEligibility};
 * {@code route} then parks on whatever the RMA is waiting for — a human task ({@code return_denial}, {@code return_review},
 * {@code refund_approval}, {@code return_disposition}; completion is applied through the matching {@code ReturnService}
 * call-back), the carrier scan / receipt (timeout → {@code return_not_received} exception → playbook), or inspection —
 * and completes once the RMA is terminal (refunded, keep_it_refunded, rejected, cancelled). Return ledger events reach
 * the instance as signals named after the event suffix ({@code received}, {@code inspected}, {@code refunded}, ...).
 */
@Component
public class ReturnWorkflow implements WorkflowDefinition {
    public static final String TYPE = "return.standard";
    static final Actor ACTOR = Actor.system("workflow");
    static final RetryPolicy RETRY = RetryPolicy.of(4, Duration.ofSeconds(1));
    /** Every {@code return.*} fact as a signal name; {@code route} re-derives the wait from the RMA state, so each wait listens to all of them. */
    static final Set<String> RETURN_SIGNALS = Set.of("eligibility_decided", "review_requested", "keep_it_offered", "carrier_scanned", "received", "inspected", "dispositioned",
        "refund_held", "refund_approved", "refunded", "refund_failed", "rejected", "cancelled");

    private final ReturnService returns;
    private final HumanTaskService tasks;
    private final ExceptionService exceptions;

    public ReturnWorkflow(ReturnService returns, HumanTaskService tasks, ExceptionService exceptions) {
        this.returns = returns;
        this.tasks = tasks;
        this.exceptions = exceptions;
    }

    @Override public String type() { return TYPE; }
    @Override public int version() { return 1; }
    @Override public String initialStep() { return "decide"; }

    @Override
    public Map<String, Step> steps() {
        Map<String, Step> m = new LinkedHashMap<>();
        m.put("decide", this::decide);
        m.put("route", this::route);
        m.put("on_task", this::onTask);
        m.put("on_event", this::onEvent);
        m.put("receipt_timeout", this::receiptTimeout);
        m.put("inspect_timeout", this::inspectTimeout);
        m.put("task_timeout", this::taskTimeout);
        return m;
    }

    Next decide(WorkflowContext ctx) {
        String rmaId = ctx.businessKey();
        Map<String, Object> result = ctx.activity("decide_eligibility", RETRY, () -> {
            ReturnRma rma = returns.getRma(rmaId);
            ctx.vars().put("orderId", rma.orderId());
            if (!ReturnStatus.REQUESTED.equals(rma.status()) || rma.reviewTaskId() != null) return summary(rma, "already decided");
            try { return summary(returns.decideEligibility(rmaId, ACTOR), "decided"); }
            catch (ConflictException e) { return summary(returns.getRma(rmaId), "conflict: " + e.getMessage()); }
        });
        ctx.vars().put("orderId", result.get("orderId"));
        ctx.log("Return eligibility decided (FR-RET-02/03)", result);
        return Next.goTo("route");
    }

    /** Deterministic dispatcher on the persisted RMA state. */
    Next route(WorkflowContext ctx) {
        ReturnRma rma = returns.getRma(ctx.businessKey());
        Map<String, Object> state = summary(rma, "route");
        if (rma.isTerminal()) {
            ctx.log("Return closed", state);
            return Next.complete(Map.of("status", rma.status(), "refundTiming", String.valueOf(rma.refundTiming()), "refunded", rma.refunded()));
        }
        if (rma.reviewTaskId() != null) {
            ctx.vars().put("task", Map.of("taskId", rma.reviewTaskId(), "type", String.valueOf(rma.reviewTaskType())));
            ctx.log("Waiting for human task " + rma.reviewTaskType() + " (T2/T3, FR-WF-04)", state);
            return Next.waitTask(rma.reviewTaskId(), "on_task", Duration.ofDays(3), "task_timeout");
        }
        if (rma.refund() != null && rma.refund().awaitingApproval() && rma.refund().approvalTaskId() != null) {
            ctx.vars().put("task", Map.of("taskId", rma.refund().approvalTaskId(), "type", ReturnTasks.TYPE_REFUND_APPROVAL));
            ctx.log("Refund awaiting finance approval (T3, GR-05)", state);
            return Next.waitTask(rma.refund().approvalTaskId(), "on_task", Duration.ofDays(3), "task_timeout");
        }
        return switch (rma.status()) {
            case ReturnStatus.REQUESTED -> Next.goTo("decide");
            case ReturnStatus.APPROVED, ReturnStatus.AWAITING_RECEIPT -> Next.waitSignal(RETURN_SIGNALS, Duration.ofDays(14), "receipt_timeout", "on_event");
            default -> Next.waitSignal(RETURN_SIGNALS, Duration.ofDays(7), "inspect_timeout", "on_event");
        };
    }

    /** Applies a completed human task through the matching {@code ReturnService} call-back (idempotent when already applied). */
    Next onTask(WorkflowContext ctx) {
        Signal sig = ctx.signal().orElseThrow();
        String taskId = sig.name().startsWith("task:") ? sig.name().substring(5) : OrderWorkflowSupport.str(sig.payload().get("taskId"));
        String outcome = OrderWorkflowSupport.str(sig.payload().get("outcome"));
        String reason = OrderWorkflowSupport.str(sig.payload().getOrDefault("reason", "decided by " + sig.payload().getOrDefault("completedBy", "human")));
        Map<String, Object> details = OrderWorkflowSupport.map(sig.payload().get("details"));
        Map<String, Object> result = ctx.activity("apply_task:" + taskId, RETRY, () -> applyTask(ctx.businessKey(), taskId, outcome, reason, details));
        ctx.log("Human task applied to the return", result);
        return Next.goTo("route");
    }

    Map<String, Object> applyTask(String rmaId, String taskId, String outcome, String reason, Map<String, Object> details) {
        ReturnRma rma = returns.getRma(rmaId);
        HumanTask task = tasks.get(taskId);
        String type = task.type();
        String o = outcome == null ? "" : outcome.trim().toLowerCase(Locale.ROOT);
        String why = reason == null || reason.isBlank() ? "task " + taskId + " completed with " + o : reason;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", taskId);
        out.put("type", type);
        out.put("outcome", o);
        boolean pending = taskId.equals(rma.reviewTaskId()) || (rma.refund() != null && taskId.equals(rma.refund().approvalTaskId()) && rma.refund().awaitingApproval());
        if (!pending) { out.put("applied", false); out.put("note", "task no longer pending on the RMA (applied through the returns API)"); return out; }
        try {
            switch (type) {
                case ReturnTasks.TYPE_RETURN_DENIAL -> returns.applyDenial(rmaId, Set.of("approved", "approve", "confirm", "confirmed", "deny", "reject", "rejected").contains(o), why, ACTOR);
                case ReturnTasks.TYPE_RETURN_REVIEW -> {
                    String timing = details.get("refundTiming") != null ? String.valueOf(details.get("refundTiming")) : RefundTiming.ACTIONABLE.contains(o) || "reject".equals(o) ? o : RefundTiming.ON_INSPECTION;
                    returns.applyReview(rmaId, timing, why, ACTOR);
                    out.put("refundTiming", timing);
                }
                case ReturnTasks.TYPE_REFUND_APPROVAL -> {
                    if (Set.of("approved", "approve", "yes", "confirmed").contains(o)) returns.approveRefund(rmaId, ACTOR);
                    else returns.reject(rmaId, why, ACTOR);
                }
                case ReturnTasks.TYPE_DISPOSITION_REVIEW -> returns.applyDisposition(rmaId, details.get("disposition") != null ? String.valueOf(details.get("disposition")) : o, why, ACTOR);
                default -> { out.put("applied", false); out.put("note", "unknown task type"); return out; }
            }
            out.put("applied", true);
        } catch (ConflictException e) {
            out.put("applied", false);
            out.put("note", "conflict: " + e.getMessage());
        }
        out.put("status", returns.getRma(rmaId).status());
        return out;
    }

    Next onEvent(WorkflowContext ctx) {
        Signal sig = ctx.signal().orElseThrow();
        ctx.log("Return event " + sig.name(), Map.of("payload", sig.payload()));
        return Next.goTo("route");
    }

    Next receiptTimeout(WorkflowContext ctx) {
        int n = OrderWorkflowSupport.intOf(ctx.vars().get("receiptTimeouts"), 0) + 1;
        ctx.vars().put("receiptTimeouts", n);
        Map<String, Object> exc = ctx.activity("return_not_received#" + n, RETRY, () -> raise("return_not_received", "RMA " + ctx.businessKey() + " approved but no carrier scan or receipt within 14 days", ctx, "return_not_received"));
        ctx.log("Return not received; playbook return_not_received triggered", exc);
        return Next.goTo("route");
    }

    Next inspectTimeout(WorkflowContext ctx) {
        int n = OrderWorkflowSupport.intOf(ctx.vars().get("inspectTimeouts"), 0) + 1;
        ctx.vars().put("inspectTimeouts", n);
        Map<String, Object> exc = ctx.activity("inspection_overdue#" + n, RETRY, () -> raise("return_inspection_overdue", "RMA " + ctx.businessKey() + " received but not inspected / settled within 7 days", ctx, null));
        ctx.log("Return inspection overdue; exception raised", exc);
        return Next.goTo("route");
    }

    Next taskTimeout(WorkflowContext ctx) {
        Map<String, Object> task = OrderWorkflowSupport.map(ctx.vars().get("task"));
        int n = OrderWorkflowSupport.intOf(ctx.vars().get("taskTimeouts"), 0) + 1;
        ctx.vars().put("taskTimeouts", n);
        Map<String, Object> exc = ctx.activity("task_timeout#" + n, RETRY, () -> raise("return_task_overdue", "Human task " + task.get("taskId") + " (" + task.get("type") + ") on RMA " + ctx.businessKey() + " is overdue", ctx, null));
        ctx.log("Return task overdue; exception raised, still waiting", exc);
        ReturnRma rma = returns.getRma(ctx.businessKey());
        String taskId = OrderWorkflowSupport.str(task.get("taskId"));
        if (taskId != null && (taskId.equals(rma.reviewTaskId()) || (rma.refund() != null && taskId.equals(rma.refund().approvalTaskId())))) {
            HumanTask t = tasks.get(taskId);
            if (t.isOpen()) return Next.waitTask(taskId, "on_task", Duration.ofDays(3), "task_timeout");
        }
        return Next.goTo("route");
    }

    private Map<String, Object> raise(String code, String message, WorkflowContext ctx, String type) {
        String orderId = OrderWorkflowSupport.str(ctx.vars().get("orderId"));
        try {
            ExceptionRecord e = exceptions.raise(new ExceptionRequest("workflow", code, message, orderId, null, null, Map.of("rmaId", ctx.businessKey())), type);
            return Map.of("exceptionId", e.id(), "type", e.type(), "queue", e.queue());
        } catch (RuntimeException ex) {
            return Map.of("error", String.valueOf(ex.getMessage()));
        }
    }

    static Map<String, Object> summary(ReturnRma rma, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rmaId", rma.rmaId());
        m.put("orderId", rma.orderId());
        m.put("status", rma.status());
        m.put("refundTiming", rma.refundTiming());
        m.put("riskScore", rma.riskScore());
        m.put("reviewTaskId", rma.reviewTaskId());
        m.put("reviewTaskType", rma.reviewTaskType());
        m.put("refunded", rma.refunded());
        m.put("note", note);
        return m;
    }
}
