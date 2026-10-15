package ai.intentoms.orchestration.actions;

import ai.intentoms.monitoring.playbook.PlaybookAction;
import ai.intentoms.monitoring.playbook.PlaybookContext;
import ai.intentoms.monitoring.playbook.PlaybookStepResult;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.modification.Feasibility;
import ai.intentoms.order.modification.Modification;
import ai.intentoms.order.modification.OrderModificationService;
import ai.intentoms.platform.error.ConflictException;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Playbook {@code cancel} (FR-MON-04, FR-CS-02, UC-07): cancels the exception's line ({@code CancelLine}) or the whole order
 * ({@code CancelOrder}, when {@code scope: order} or the exception has no line) through the modification service, which
 * applies the feasibility check, fires {@code api.cancel_line} / {@code api.cancel_order} and signals the workflow.
 */
@Component
public class CancelAction implements PlaybookAction {
    private final OrderModificationService modifications;
    private final ActionSupport support;

    public CancelAction(OrderModificationService modifications, ActionSupport support) {
        this.modifications = modifications;
        this.support = support;
    }

    @Override public String action() { return "cancel"; }

    @Override
    public PlaybookStepResult run(PlaybookContext ctx, Map<String, Object> step) {
        if (support.order(ctx).isEmpty()) return PlaybookStepResult.skipped("no order to cancel");
        Order o = support.order(ctx).get();
        String lineId = ActionSupport.str(step, "lineId", ctx.exception().lineId());
        Modification m = lineId != null && !"order".equals(step.get("scope")) ? new Modification.CancelLine(lineId) : new Modification.CancelOrder();
        String reason = ActionSupport.str(step, "reason", "playbook cancel for exception " + ctx.exception().id() + " (" + ctx.exception().type() + ")");
        try {
            Feasibility f = modifications.check(o.id(), m);
            if (!f.allowed()) return PlaybookStepResult.skipped("cancel not feasible: " + f.reasons());
            modifications.apply(o.id(), m, reason, ctx.actor());
            return PlaybookStepResult.done((m instanceof Modification.CancelLine ? "cancelled line " + lineId : "cancelled order") + " of " + o.id(), Map.of("modification", m.type(), "lineId", lineId == null ? "" : lineId));
        } catch (ConflictException e) {
            return PlaybookStepResult.skipped("cancel refused: " + e.getMessage());
        }
    }
}
