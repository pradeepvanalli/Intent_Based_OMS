package ai.intentoms.orchestration.actions;

import ai.intentoms.finance.model.Money;
import ai.intentoms.monitoring.playbook.PlaybookAction;
import ai.intentoms.monitoring.playbook.PlaybookContext;
import ai.intentoms.monitoring.playbook.PlaybookStepResult;
import ai.intentoms.orchestration.workflow.Signals;
import ai.intentoms.order.model.FulfilmentGroup;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.model.OrderLine;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.sourcing.SourcingService;
import ai.intentoms.sourcing.model.SourcingResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Playbook {@code reship} (FR-MON-04, GR-05): creates a new fulfilment group for the affected lines by re-sourcing them with
 * reason {@code reship}, gated by the tenant's {@code reship_usd} spend limit (value of the lines being re-shipped) and the
 * step's own {@code spendLimit}. Only lines still in an active (unshipped) group can be re-released; shipped goods are
 * handled through a return / replacement order.
 */
@Component
public class ReshipAction implements PlaybookAction {
    private final SourcingService sourcing;
    private final ActionSupport support;

    public ReshipAction(SourcingService sourcing, ActionSupport support) {
        this.sourcing = sourcing;
        this.support = support;
    }

    @Override public String action() { return "reship"; }

    @Override
    public PlaybookStepResult run(PlaybookContext ctx, Map<String, Object> step) {
        Optional<Order> order = support.order(ctx);
        if (order.isEmpty()) return PlaybookStepResult.skipped("no order to re-ship");
        Order o = order.get();
        String nodeId = ActionSupport.str(step, "nodeId", ctx.exception().nodeId());
        List<FulfilmentGroup> groups = ActionSupport.activeGroups(o, nodeId);
        if (groups.isEmpty()) groups = ActionSupport.activeGroups(o, null);
        if (groups.isEmpty()) return PlaybookStepResult.skipped("no active fulfilment group to re-ship (shipped goods need a return or replacement order)");
        Money value = Money.zero(o.currency());
        for (FulfilmentGroup g : groups) for (FulfilmentGroup.Allocation a : g.allocations()) {
            OrderLine l = o.line(a.lineId()).orElse(null);
            if (l != null && l.qty() > 0) value = value.add(l.unitPrice().multiply(a.qty()));
        }
        double limit = ctx.spendLimit("reship_usd").orElse(ActionSupport.num(step.get("spendLimit"), Double.MAX_VALUE));
        if (value.amount().doubleValue() > limit) return PlaybookStepResult.skipped(String.format(java.util.Locale.ROOT, "re-ship value %s exceeds the reship limit %.2f (GR-05)", value, limit));
        try {
            SourcingResult r = sourcing.resource(o.id(), groups.size() == 1 ? groups.get(0).nodeId() : null, "reship", ctx.actor());
            Map<String, Object> payload = Map.of("exceptionId", ctx.exception().id(), "status", r.status().name(), "planId", r.plan() == null ? "" : r.plan().id(), "reason", "reship");
            support.signal(o, Signals.RESOURCED, payload, ctx.exception().id() + ":reship");
            String detail = "re-ship of " + value + " planned: " + r.status() + (r.plan() == null ? "" : " via " + r.plan().packages().stream().map(p -> p.nodeId()).toList());
            return r.status() == SourcingResult.Status.NO_FEASIBLE_NODE ? PlaybookStepResult.failed(detail) : PlaybookStepResult.done(detail, payload);
        } catch (ConflictException e) {
            return PlaybookStepResult.skipped("nothing to re-ship: " + e.getMessage());
        }
    }
}
