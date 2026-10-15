package ai.intentoms.orchestration.workflow;

import ai.intentoms.orchestration.config.OrchestrationConfig;
import ai.intentoms.orchestration.config.OrchestrationConfigKeys;
import ai.intentoms.orchestration.config.OrchestrationProperties;
import ai.intentoms.orchestration.integration.CustomerNotifier;
import ai.intentoms.orchestration.integration.WmsConnector;
import ai.intentoms.order.model.FulfilmentGroup;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.model.OrderLine;
import ai.intentoms.order.service.IntentService;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.ledger.NewEvent;
import ai.intentoms.sourcing.SourcingService;
import ai.intentoms.workflow.model.Next;
import ai.intentoms.workflow.model.Signal;
import ai.intentoms.workflow.model.Step;
import ai.intentoms.workflow.model.WorkflowContext;
import ai.intentoms.workflow.task.HumanTaskService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code order.bopis} v1 (ARCHITECTURE §3.13, UC-04 pickup intent): validate → payment_authorize → source (pickup store) →
 * reserve → release (pick request to the store) → await_pick ({@code picked}, {@code staged_for_pickup}) → await_pickup
 * ({@code picked_up} captures payment; the pickup window timer fires {@code timer.pickup_window_expired}, releases stock
 * and voids the authorisation).
 */
@Component
public class BopisOrderWorkflow extends AbstractOrderWorkflow {
    public static final String TYPE = "order.bopis";
    static final Set<String> PICK_SIGNALS = Set.of(Signals.PICKED, Signals.STAGED_FOR_PICKUP, Signals.STORE_REJECTED, Signals.SHORT_PICK, Signals.CANCEL_REQUESTED, Signals.RESOURCED, Signals.ORDER_MODIFIED);
    static final Set<String> PICKUP_SIGNALS = Set.of(Signals.PICKED_UP, Signals.CANCEL_REQUESTED, Signals.PICKUP_WINDOW_EXPIRED, Signals.ORDER_MODIFIED);

    public BopisOrderWorkflow(OrderWorkflowSupport support, SourcingService sourcing, IntentService intents, HumanTaskService tasks, WmsConnector wms, CustomerNotifier notifier,
                              TenantConfigStore config, OrchestrationProperties props, ObjectProvider<AgentVerificationHook> agentHook) {
        super(support, sourcing, intents, tasks, wms, notifier, config, props, agentHook);
    }

    @Override public String type() { return TYPE; }
    @Override public int version() { return 1; }
    @Override protected boolean infersIntent() { return false; }
    @Override protected String afterReleaseStep() { return "await_pick"; }

    @Override
    protected Map<String, Step> fulfilmentSteps() {
        Map<String, Step> m = new LinkedHashMap<>();
        m.put("await_pick", this::awaitPick);
        m.put("on_pick_event", this::onPickEvent);
        m.put("await_pickup", this::awaitPickup);
        m.put("on_pickup_event", this::onPickupEvent);
        m.put("pickup_expired", this::pickupExpired);
        return m;
    }

    Next awaitPick(WorkflowContext ctx) { return Next.waitSignal(PICK_SIGNALS, fulfilmentTimeout(), "fulfilment_timeout", "on_pick_event"); }

    Next onPickEvent(WorkflowContext ctx) {
        Signal sig = ctx.signal().orElseThrow();
        int n = OrderWorkflowSupport.intOf(ctx.vars().get("signals"), 0) + 1;
        ctx.vars().put("signals", n);
        Map<String, Object> payload = jsonPayload(sig);
        Order order = order(ctx);
        switch (sig.name()) {
            case Signals.PICKED -> {
                List<Map<String, Object>> moved = ctx.activity("picked#" + n, RETRY, () -> fireAffected(order, payload, "event.picked", false));
                ctx.log("Store picked the order", Map.of("transitions", moved));
                return Next.goTo("await_pick");
            }
            case Signals.STAGED_FOR_PICKUP -> {
                Map<String, Object> result = ctx.activity("staged#" + n, RETRY, () -> {
                    List<Map<String, Object>> moved = fireAffected(order, payload, "event.picked", true);
                    moved.addAll(fireAffected(order, payload, "event.staged_for_pickup", false));
                    Order fresh = s.orders().get(order.id());
                    boolean ready = s.allLinesIn(fresh, Set.of("ready_for_pickup", "picked_up", "cancelled"));
                    if (ready) {
                        s.appendOrderEvent(order.id(), "order.ready_for_pickup", Map.of("nodeId", String.valueOf(pickupNode(fresh))));
                        notifier.notify(order.id(), "email", "ready_for_pickup", "Your order is ready for pickup", "Order " + order.id() + " is ready at " + pickupNode(fresh), Map.of("nodeId", String.valueOf(pickupNode(fresh))));
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("transitions", moved);
                    m.put("ready", ready);
                    return m;
                });
                ctx.log("Order staged for pickup", result);
                return Next.goTo(Boolean.TRUE.equals(result.get("ready")) ? "await_pickup" : "await_pick");
            }
            case Signals.STORE_REJECTED, Signals.SHORT_PICK -> {
                Map<String, Object> result = ctx.activity(sig.name() + "#" + n, RETRY, () -> storeReject(order, sig.name(), payload));
                ctx.log("Store rejected the pick; re-sourced (FR-SRC-07)", result);
                String status = OrderWorkflowSupport.str(result.get("status"));
                if ("SOURCED".equals(status)) { ctx.vars().put("cycle", OrderWorkflowSupport.intOf(ctx.vars().get("cycle"), 0) + 1); ctx.vars().remove("reserveRetries"); return Next.goTo("reserve"); }
                if ("REQUIRES_APPROVAL".equals(status)) return Next.goTo("source");
                if ("NO_FEASIBLE_NODE".equals(status)) return Next.goTo("backorder");
                return Next.goTo("await_pick");
            }
            case Signals.RESOURCED -> { return Next.goTo(s.anyLineIn(order, "awaiting_sourcing") ? "reserve" : "await_pick"); }
            case Signals.CANCEL_REQUESTED -> { ctx.vars().put("cancelReason", "cancel_requested"); return Next.goTo("cancel_order"); }
            case Signals.ORDER_MODIFIED -> { return s.allLinesIn(order, Set.of("cancelled")) ? finishCancelled(ctx, order, n) : Next.goTo("await_pick"); }
            default -> { return Next.goTo("await_pick"); }
        }
    }

    Next awaitPickup(WorkflowContext ctx) {
        return Next.waitSignal(PICKUP_SIGNALS, OrchestrationConfig.duration(config, OrchestrationConfigKeys.PICKUP_WINDOW, props.pickupWindow()), "pickup_expired", "on_pickup_event");
    }

    Next onPickupEvent(WorkflowContext ctx) {
        Signal sig = ctx.signal().orElseThrow();
        int n = OrderWorkflowSupport.intOf(ctx.vars().get("signals"), 0) + 1;
        ctx.vars().put("signals", n);
        Map<String, Object> payload = jsonPayload(sig);
        Order order = order(ctx);
        switch (sig.name()) {
            case Signals.PICKED_UP -> {
                Map<String, Object> result = ctx.activity("picked_up#" + n, RETRY, () -> pickedUp(order, payload));
                ctx.log("Customer picked up the order; payment captured (FR-FIN-01)", result);
                if (Boolean.TRUE.equals(result.get("allPickedUp"))) return Next.complete(Map.of("status", "picked_up", "captured", result.getOrDefault("captured", 0)));
                return Next.goTo("await_pickup");
            }
            case Signals.PICKUP_WINDOW_EXPIRED -> { return Next.goTo("pickup_expired"); }
            case Signals.CANCEL_REQUESTED -> { ctx.vars().put("cancelReason", "cancel_requested"); return Next.goTo("cancel_order"); }
            case Signals.ORDER_MODIFIED -> { return s.allLinesIn(order, Set.of("cancelled")) ? finishCancelled(ctx, order, n) : Next.goTo("await_pickup"); }
            default -> { return Next.goTo("await_pickup"); }
        }
    }

    Map<String, Object> pickedUp(Order order, Map<String, Object> payload) {
        List<Map<String, Object>> moved = fireAffected(order, payload, "event.picked_up", false);
        Order fresh = s.orders().get(order.id());
        boolean all = s.allLinesIn(fresh, Set.of("picked_up", "cancelled"));
        Map<String, Integer> byLine = new LinkedHashMap<>();
        for (Map<String, Object> t : moved) if (Boolean.TRUE.equals(t.get("applied"))) byLine.merge(OrderWorkflowSupport.str(t.get("lineId")), OrderWorkflowSupport.intOf(t.get("qty"), 0), Integer::sum);
        Map<String, Object> capture = s.captureForShipment(fresh, byLine, all, "pickup " + order.id());
        String node = pickupNode(fresh);
        Map<String, Integer> cumulative = new LinkedHashMap<>();
        for (OrderLine l : fresh.lines()) { int q = s.qtyIn(fresh, l.lineId(), "picked_up"); if (q > 0) cumulative.merge(l.sku() + "|" + node, q, Integer::sum); }
        List<String> consumed = node == null ? List.of() : s.consumeReservations(order.id(), node, cumulative);
        Set<String> groups = new LinkedHashSet<>();
        if (all) for (FulfilmentGroup g : OrderWorkflowSupport.activeGroups(fresh)) groups.add(g.id());
        Map<String, Object> evt = new LinkedHashMap<>();
        evt.put("nodeId", String.valueOf(node));
        evt.put("lines", byLine);
        evt.put("captured", capture.get("captured"));
        evt.put("allPickedUp", all);
        s.markGroups(order.id(), groups, "picked_up", new NewEvent("order.picked_up", evt, OrderWorkflowSupport.ACTOR, null, order.id(), Map.of()));
        Map<String, Object> out = new LinkedHashMap<>(evt);
        out.put("transitions", moved);
        out.put("reservationsConsumed", consumed);
        out.put("capture", capture);
        return out;
    }

    Next pickupExpired(WorkflowContext ctx) {
        Order order = order(ctx);
        Map<String, Object> result = ctx.activity("pickup_expired", RETRY, () -> {
            List<Map<String, Object>> moved = new ArrayList<>();
            for (OrderLine l : order.lines()) if (s.qtyIn(order, l.lineId(), "ready_for_pickup") > 0) moved.add(s.fireLine(order.id(), l.lineId(), "timer.pickup_window_expired", null, Map.of()));
            Map<String, Object> m = new LinkedHashMap<>(cancelEverything(s.orders().get(order.id()), "pickup_window_expired"));
            m.put("pickupTransitions", moved);
            notifier.notify(order.id(), "email", "pickup_cancelled", "Your pickup order was cancelled", "Order " + order.id() + " was not collected within the pickup window", Map.of());
            return m;
        });
        ctx.log("Pickup window expired; order cancelled and authorisation voided", result);
        return Next.complete(Map.of("status", "cancelled", "reason", "pickup_window_expired"));
    }

    private Next finishCancelled(WorkflowContext ctx, Order order, int n) {
        Map<String, Object> result = ctx.activity("cancelled_by_modification#" + n, RETRY, () -> {
            List<String> released = s.releaseReservations(order.id(), "order cancelled");
            boolean voided = false;
            if (!s.captured(order.id(), order.currency()).isPositive()) { s.voidAuthorization(order.id(), "order cancelled"); voided = true; }
            for (FulfilmentGroup g : OrderWorkflowSupport.activeGroups(order)) wms.cancelRelease(order, g, "order cancelled");
            return Map.of("reservationsReleased", released, "authVoided", voided);
        });
        ctx.log("All lines cancelled through CS modification; closing", result);
        return Next.complete(Map.of("status", "cancelled", "reason", "modified"));
    }

    private Map<String, Object> storeReject(Order order, String kind, Map<String, Object> payload) {
        FulfilmentGroup group = resolveGroup(order, payload);
        String nodeId = group != null ? group.nodeId() : OrderWorkflowSupport.str(payload.get("nodeId"));
        List<Map<String, Object>> moved = fireAffected(order, payload, "event.store_rejected", false);
        Set<String> skus = new LinkedHashSet<>();
        for (var e : affectedLines(order, group, payload).entrySet()) order.line(e.getKey()).ifPresent(l -> skus.add(l.sku()));
        List<String> released = s.releaseReservationsAt(order.id(), nodeId, skus, kind);
        Map<String, Object> resource;
        try { resource = planSummary(sourcing.resource(order.id(), nodeId, kind, OrderWorkflowSupport.ACTOR)); }
        catch (ConflictException e) { resource = Map.of("status", "NO_ACTIVE_GROUP", "error", String.valueOf(e.getMessage())); }
        Map<String, Object> exc = s.raise("wms", kind, "Store " + nodeId + " could not fulfil the pickup order", order.id(), null, nodeId, Map.of("resourceStatus", resource.get("status")), null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("transitions", moved);
        out.put("reservationsReleased", released);
        out.put("resource", resource);
        out.put("status", resource.get("status"));
        out.put("exception", exc);
        return out;
    }

    /** Fires {@code trigger} on the lines a store event covers; {@code onlyIfReleased} limits it to units still in {@code released}. */
    private List<Map<String, Object>> fireAffected(Order order, Map<String, Object> payload, String trigger, boolean onlyIfReleased) {
        FulfilmentGroup group = resolveGroup(order, payload);
        Map<String, Integer> explicit = payloadLines(payload);
        List<Map<String, Object>> out = new ArrayList<>();
        for (var e : affectedLines(order, group, payload).entrySet()) {
            if (onlyIfReleased) {
                int q = Math.min(e.getValue(), s.qtyIn(order, e.getKey(), "released"));
                if (q > 0) out.add(s.fireLine(order.id(), e.getKey(), trigger, q, Map.of()));
            } else out.add(s.fireLine(order.id(), e.getKey(), trigger, explicit.isEmpty() ? null : e.getValue(), Map.of()));
        }
        return out;
    }

    private static String pickupNode(Order order) {
        return OrderWorkflowSupport.activeGroups(order).stream().map(FulfilmentGroup::nodeId).findFirst()
            .orElse(order.fulfilmentGroups().isEmpty() ? null : order.fulfilmentGroups().get(0).nodeId());
    }
}
