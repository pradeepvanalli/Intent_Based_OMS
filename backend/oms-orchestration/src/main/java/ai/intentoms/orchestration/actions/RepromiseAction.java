package ai.intentoms.orchestration.actions;

import ai.intentoms.monitoring.playbook.PlaybookAction;
import ai.intentoms.monitoring.playbook.PlaybookContext;
import ai.intentoms.monitoring.playbook.PlaybookStepResult;
import ai.intentoms.orchestration.integration.CustomerNotifier;
import ai.intentoms.order.model.FulfilmentGroup;
import ai.intentoms.order.model.Order;
import ai.intentoms.platform.ledger.NewEvent;
import ai.intentoms.sourcing.SourcingService;
import ai.intentoms.sourcing.model.PlanPackage;
import ai.intentoms.sourcing.model.SourcingPlan;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Playbook {@code repromise} (FR-MON-04, FR-SRC-05, UC-13): recomputes the promise of every active fulfilment group from the
 * current sourcing options (same node when still offered, else the best plan's package for the lines), stores the new
 * promise date / on-time probability on the groups with {@code order.repromised}, and tells the customer when a date moved
 * later ({@code template}, default {@code delay_notice}).
 */
@Component
public class RepromiseAction implements PlaybookAction {
    private final SourcingService sourcing;
    private final ActionSupport support;
    private final CustomerNotifier notifier;

    public RepromiseAction(SourcingService sourcing, ActionSupport support, CustomerNotifier notifier) {
        this.sourcing = sourcing;
        this.support = support;
        this.notifier = notifier;
    }

    @Override public String action() { return "repromise"; }

    @Override
    public PlaybookStepResult run(PlaybookContext ctx, Map<String, Object> step) {
        Optional<Order> order = support.order(ctx);
        if (order.isEmpty()) return PlaybookStepResult.skipped("no order to re-promise");
        Map<String, Object> result = repromise(order.get(), ActionSupport.str(step, "template", "delay_notice"), ctx.exception().id(), "playbook " + ctx.exception().type());
        if (Boolean.TRUE.equals(result.get("skipped"))) return PlaybookStepResult.skipped(String.valueOf(result.get("detail")));
        return PlaybookStepResult.done(String.valueOf(result.get("detail")), result);
    }

    /** Re-promise shared with the bulk handler; returns a JSON-friendly summary ({@code skipped}, {@code detail}, {@code groups}). */
    public Map<String, Object> repromise(Order order, String template, String causeId, String reason) {
        List<FulfilmentGroup> active = ActionSupport.activeGroups(order, null);
        Map<String, Object> out = new LinkedHashMap<>();
        if (active.isEmpty()) { out.put("skipped", true); out.put("detail", "no active fulfilment group"); return out; }
        List<SourcingPlan> options = sourcing.options(order.id());
        if (options.isEmpty()) { out.put("skipped", true); out.put("detail", "no sourcing option available"); return out; }
        List<Map<String, Object>> changes = new ArrayList<>();
        List<FulfilmentGroup> updated = new ArrayList<>();
        boolean later = false;
        for (FulfilmentGroup g : order.fulfilmentGroups()) {
            if (!active.contains(g)) { updated.add(g); continue; }
            PlanPackage pk = packageFor(options, g);
            if (pk == null || pk.eta() == null) { updated.add(g); continue; }
            LocalDate old = g.promiseDate();
            boolean moved = old == null || !old.equals(pk.eta());
            if (old != null && pk.eta().isAfter(old)) later = true;
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("groupId", g.id());
            c.put("nodeId", g.nodeId());
            c.put("oldPromise", old == null ? null : old.toString());
            c.put("newPromise", pk.eta().toString());
            c.put("onTimeProbability", pk.onTimeProbability());
            c.put("changed", moved);
            changes.add(c);
            updated.add(new FulfilmentGroup(g.id(), g.nodeId(), g.policy(), g.allocations(), pk.eta(), pk.onTimeProbability(), g.shipMethod(), g.estimatedCost(), g.status(), g.sourcingPlanId(), g.decisionRecordId(), g.explain()));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("groups", changes);
        payload.put("reason", reason);
        if (causeId != null) payload.put("exceptionId", causeId);
        support.orders().update(order.id(), o -> o.withFulfilmentGroups(updated), new NewEvent("order.repromised", payload, OrderActor.ACTOR, null, order.id(), Map.of()));
        String detail = "re-promised " + changes.size() + " group(s)" + (later ? "; promise moved later, customer notified" : "");
        if (later && template != null && !template.isBlank()) {
            String dates = changes.stream().map(c -> String.valueOf(c.get("newPromise"))).distinct().toList().toString();
            notifier.notify(order.id(), "email", template, "Update on your delivery date", "Your order " + order.id() + " is now expected " + dates, Map.of("promiseDates", dates));
        }
        out.put("skipped", false);
        out.put("detail", detail);
        out.put("groups", changes);
        out.put("later", later);
        return out;
    }

    /** The plan package for the group's node from the best plan that still uses it, else the best plan's package covering the group's first line. */
    private static PlanPackage packageFor(List<SourcingPlan> options, FulfilmentGroup g) {
        for (SourcingPlan p : options) for (PlanPackage pk : p.packages()) if (pk.nodeId().equals(g.nodeId())) return pk;
        String firstLine = g.allocations().isEmpty() ? null : g.allocations().get(0).lineId();
        for (SourcingPlan p : options) for (PlanPackage pk : p.packages()) if (pk.allocations().stream().anyMatch(a -> a.lineId().equals(firstLine))) return pk;
        return options.get(0).packages().isEmpty() ? null : options.get(0).packages().get(0);
    }
}
