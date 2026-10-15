package ai.intentoms.orchestration.actions;

import ai.intentoms.monitoring.playbook.PlaybookAction;
import ai.intentoms.monitoring.playbook.PlaybookContext;
import ai.intentoms.monitoring.playbook.PlaybookStepResult;
import ai.intentoms.orchestration.workflow.Signals;
import ai.intentoms.order.model.Order;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.sourcing.SourcingService;
import ai.intentoms.sourcing.model.SourcingResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Playbook {@code resource} (FR-MON-04, FR-SRC-07, UC-06/UC-13): penalises the rejecting node and re-sources the order's
 * active fulfilment groups (at the exception's node when known), then signals the order workflow {@code resourced} so it
 * reserves and releases the new plan. Skipped when the workflow already re-sourced (no active group at the node).
 */
@Component
public class ResourceAction implements PlaybookAction {
    private final SourcingService sourcing;
    private final ActionSupport support;

    public ResourceAction(SourcingService sourcing, ActionSupport support) {
        this.sourcing = sourcing;
        this.support = support;
    }

    @Override public String action() { return "resource"; }

    @Override
    public PlaybookStepResult run(PlaybookContext ctx, Map<String, Object> step) {
        Optional<Order> order = support.order(ctx);
        if (order.isEmpty()) return PlaybookStepResult.skipped("no order to re-source");
        String nodeId = ActionSupport.str(step, "nodeId", ctx.exception().nodeId());
        if (ActionSupport.activeGroups(order.get(), nodeId).isEmpty()) {
            if (nodeId != null && !ActionSupport.activeGroups(order.get(), null).isEmpty()) nodeId = null;
            else return PlaybookStepResult.skipped("no active fulfilment group to re-source" + (nodeId == null ? "" : " at " + nodeId));
        }
        String reason = ActionSupport.str(step, "reason", "playbook:" + ctx.exception().type() + ":" + ctx.exception().code());
        try {
            SourcingResult r = sourcing.resource(order.get().id(), nodeId, reason, ctx.actor());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("exceptionId", ctx.exception().id());
            payload.put("nodeId", nodeId == null ? "" : nodeId);
            payload.put("status", r.status().name());
            payload.put("planId", r.plan() == null ? "" : r.plan().id());
            boolean signalled = support.signal(order.get(), Signals.RESOURCED, payload, ctx.exception().id() + ":resourced");
            String detail = "re-sourced " + order.get().id() + (nodeId == null ? "" : " away from " + nodeId) + ": " + r.status() + (r.plan() == null ? "" : " plan " + r.plan().id() + " (" + r.plan().policy() + ")") + (signalled ? "; workflow signalled" : "");
            return r.status() == SourcingResult.Status.NO_FEASIBLE_NODE ? PlaybookStepResult.failed(detail) : PlaybookStepResult.done(detail, payload);
        } catch (ConflictException e) {
            return PlaybookStepResult.skipped("nothing to re-source: " + e.getMessage());
        }
    }
}
