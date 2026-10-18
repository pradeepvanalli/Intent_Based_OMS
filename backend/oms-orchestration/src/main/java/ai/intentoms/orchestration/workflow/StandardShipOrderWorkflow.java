package ai.intentoms.orchestration.workflow;

import ai.intentoms.orchestration.config.OrchestrationConfig;
import ai.intentoms.orchestration.config.OrchestrationConfigKeys;
import ai.intentoms.orchestration.config.OrchestrationProperties;
import ai.intentoms.orchestration.integration.CustomerNotifier;
import ai.intentoms.orchestration.integration.WmsConnector;
import ai.intentoms.orchestration.shipment.ShipmentRecord;
import ai.intentoms.orchestration.shipment.ShipmentService;
import ai.intentoms.order.model.FulfilmentGroup;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.model.OrderLine;
import ai.intentoms.order.service.IntentService;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.sourcing.SourcingService;
import ai.intentoms.statemachine.model.EntityStatus;
import ai.intentoms.workflow.model.Next;
import ai.intentoms.workflow.model.Signal;
import ai.intentoms.workflow.model.Step;
import ai.intentoms.workflow.model.WorkflowContext;
import ai.intentoms.workflow.task.HumanTaskService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code order.standard_ship} v1 (ARCHITECTURE §3.13; FR-WF-01/02/05/06; UC-01..UC-07): validate → infer_intent →
 * verify_agent → payment_authorize → source (margin floor approval loop) → reserve → release → await_fulfilment
 * (shipped | store_rejected | short_pick | pick_started | packed | resourced | cancel_requested | order.modified) →
 * await_delivery (delivered | carrier_exception | returned_to_sender) → complete. Store rejects and short picks loop back
 * through re-sourcing (FR-SRC-07); a shipment captures payment (FR-FIN-01) and consumes reservations (FR-INV-02).
 */
@Component
public class StandardShipOrderWorkflow extends AbstractOrderWorkflow {
    public static final String TYPE = "order.standard_ship";
    static final Set<String> FULFILMENT_SIGNALS = Set.of(Signals.SHIPPED, Signals.STORE_REJECTED, Signals.SHORT_PICK, Signals.CANCEL_REQUESTED, Signals.PICK_STARTED, Signals.PACKED,
        Signals.RESOURCED, Signals.ORDER_MODIFIED);
    static final Set<String> DELIVERY_SIGNALS = Set.of(Signals.DELIVERED, Signals.CARRIER_EXCEPTION, Signals.RETURNED_TO_SENDER, Signals.ORDER_MODIFIED);

    private final ShipmentService shipments;

    public StandardShipOrderWorkflow(OrderWorkflowSupport support, SourcingService sourcing, IntentService intents, HumanTaskService tasks, WmsConnector wms, CustomerNotifier notifier,
                                     TenantConfigStore config, OrchestrationProperties props, ObjectProvider<AgentVerificationHook> agentHook, ShipmentService shipments) {
        super(support, sourcing, intents, tasks, wms, notifier, config, props, agentHook);
        this.shipments = shipments;
    }

    @Override public String type() { return TYPE; }
    @Override public int version() { return 1; }
    @Override protected String afterReleaseStep() { return "await_fulfilment"; }

    @Override
    protected Map<String, Step> fulfilmentSteps() {
        Map<String, Step> m = new LinkedHashMap<>();
        m.put("await_fulfilment", this::awaitFulfilment);
        m.put("on_fulfilment_event", this::onFulfilmentEvent);
        m.put("await_delivery", this::awaitDelivery);
        m.put("on_delivery_event", this::onDeliveryEvent);
        m.put("delivery_timeout", this::deliveryTimeout);
        return m;
    }

    // ------------------------------------------------------------------ fulfilment (UC-05, UC-06)

    Next awaitFulfilment(WorkflowContext ctx) {
        return Next.waitSignal(FULFILMENT_SIGNALS, fulfilmentTimeout(), "fulfilment_timeout", "on_fulfilment_event");
    }

    Next onFulfilmentEvent(WorkflowContext ctx) {
        Signal sig = ctx.signal().orElseThrow();
        int n = OrderWorkflowSupport.intOf(ctx.vars().get("signals"), 0) + 1;
        ctx.vars().put("signals", n);
        Map<String, Object> payload = jsonPayload(sig);
        Order order = order(ctx);
        return switch (sig.name()) {
            case Signals.SHIPPED -> onShipped(ctx, order, payload, n);
            case Signals.STORE_REJECTED, Signals.SHORT_PICK -> onReject(ctx, order, sig.name(), payload, n);
            case Signals.PICK_STARTED, Signals.PACKED -> onPickProgress(ctx, order, sig.name(), payload, n);
            case Signals.RESOURCED -> {
                ctx.log("Re-sourced by playbook", payload);
                yield s.anyLineIn(order, "awaiting_sourcing") ? Next.goTo("reserve") : Next.goTo("await_fulfilment");
            }
            case Signals.CANCEL_REQUESTED -> { ctx.vars().put("cancelReason", "cancel_requested"); yield Next.goTo("cancel_order"); }
            case Signals.ORDER_MODIFIED -> onModified(ctx, order, payload, n, "await_fulfilment");
            default -> Next.goTo("await_fulfilment");
        };
    }

    private Next onShipped(WorkflowContext ctx, Order order, Map<String, Object> payload, int n) {
        Map<String, Object> result = ctx.activity("ship#" + n, RETRY, () -> ship(order, payload));
        ctx.log("Shipment recorded; payment captured on shipment (FR-FIN-01)", result);
        return Next.goTo(Boolean.TRUE.equals(result.get("allShipped")) ? "await_delivery" : "await_fulfilment");
    }

    /** Lines → picking → packed → shipped for the shipped units, shipment row + state machine, reservations consumed, capture. */
    Map<String, Object> ship(Order order, Map<String, Object> payload) {
        FulfilmentGroup group = resolveGroup(order, payload);
        String nodeId = group != null ? group.nodeId() : OrderWorkflowSupport.str(payload.get("nodeId"));
        Map<String, Integer> wanted = affectedLines(order, group, payload);
        Map<String, Integer> shippedByLine = new LinkedHashMap<>();
        List<ShipmentRecord.Line> shipLines = new ArrayList<>();
        List<Map<String, Object>> transitions = new ArrayList<>();
        for (var e : wanted.entrySet()) {
            OrderLine line = order.line(e.getKey()).orElse(null);
            if (line == null || e.getValue() <= 0) continue;
            EntityStatus st = s.lineStatus(order.id(), line.lineId());
            int q = Math.min(e.getValue(), st.qtyIn("released") + st.qtyIn("picking") + st.qtyIn("packed"));
            if (q <= 0) continue;
            int fromReleased = Math.min(q, st.qtyIn("released"));
            if (fromReleased > 0) transitions.add(s.fireLine(order.id(), line.lineId(), "event.pick_started", fromReleased, Map.of()));
            int toPack = Math.min(q, st.qtyIn("picking") + fromReleased);
            if (toPack > 0) transitions.add(s.fireLine(order.id(), line.lineId(), "event.packed", toPack, Map.of()));
            Map<String, Object> shipped = s.fireLine(order.id(), line.lineId(), "event.shipped", q, Map.of());
            transitions.add(shipped);
            if (Boolean.TRUE.equals(shipped.get("applied"))) {
                shippedByLine.put(line.lineId(), q);
                shipLines.add(new ShipmentRecord.Line(line.lineId(), line.sku(), q));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("transitions", transitions);
        if (shipLines.isEmpty()) {
            out.put("skipped", "no shippable units in the payload");
            out.put("allShipped", s.allLinesIn(order, FINISHED_LINE));
            return out;
        }
        Integer transit = group == null || group.promiseDate() == null ? null : (int) Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(java.time.ZoneOffset.UTC), group.promiseDate()));
        ShipmentRecord shipment = shipments.create(order.id(), group == null ? null : group.id(), nodeId, OrderWorkflowSupport.str(payload.get("carrier")),
            OrderWorkflowSupport.str(payload.getOrDefault("service", order.shipMethod())), OrderWorkflowSupport.str(payload.get("tracking")), shipLines, transit, OrderWorkflowSupport.ACTOR);
        Map<String, Integer> cumulative = new LinkedHashMap<>();
        for (ShipmentRecord.Line l : shipLines) cumulative.merge(l.sku() + "|" + nodeId, l.qty(), Integer::sum);
        List<String> consumed = nodeId == null ? List.of() : s.consumeReservations(order.id(), nodeId, cumulativeShipped(order, nodeId, cumulative));
        Order fresh = s.orders().get(order.id());
        boolean allShipped = s.allLinesIn(fresh, FINISHED_LINE);
        Map<String, Object> capture = s.captureForShipment(fresh, shippedByLine, allShipped, "shipment " + shipment.id());
        Set<String> groupsDone = new LinkedHashSet<>();
        if (group != null && group.allocations().stream().allMatch(a -> s.qtyIn(fresh, a.lineId(), "released") + s.qtyIn(fresh, a.lineId(), "picking") + s.qtyIn(fresh, a.lineId(), "packed") == 0)) groupsDone.add(group.id());
        Map<String, Object> evt = new LinkedHashMap<>();
        evt.put("shipmentId", shipment.id());
        evt.put("nodeId", nodeId);
        evt.put("carrier", shipment.carrier());
        evt.put("tracking", shipment.tracking());
        evt.put("lines", shippedByLine);
        evt.put("captured", capture.get("captured"));
        evt.put("allShipped", allShipped);
        if (!groupsDone.isEmpty()) s.markGroups(order.id(), groupsDone, "shipped", null);
        s.appendOrderEvent(order.id(), "order.shipped", evt);
        notifier.notify(order.id(), "email", "shipped", "Your order has shipped", "Shipment " + shipment.tracking() + " (" + shipment.carrier() + ") with " + shipLines.size() + " line(s)",
            Map.of("tracking", String.valueOf(shipment.tracking()), "carrier", String.valueOf(shipment.carrier())));
        out.put("shipmentId", shipment.id());
        out.put("tracking", shipment.tracking());
        out.put("nodeId", nodeId);
        out.put("lines", shippedByLine);
        out.put("reservationsConsumed", consumed);
        out.put("capture", capture);
        out.put("allShipped", allShipped);
        return out;
    }

    /** Cumulative shipped units per {@code sku|node} across the order's shipments (so partial shipments consume reservations once covered). */
    private Map<String, Integer> cumulativeShipped(Order order, String nodeId, Map<String, Integer> thisShipment) {
        Map<String, Integer> total = new LinkedHashMap<>();
        for (ShipmentRecord sh : shipments.forOrder(order.id())) {
            if (!nodeId.equals(sh.nodeId())) continue;
            for (ShipmentRecord.Line l : sh.lines()) total.merge(l.sku() + "|" + nodeId, l.qty(), Integer::sum);
        }
        if (total.isEmpty()) total.putAll(thisShipment);
        return total;
    }

    private Next onReject(WorkflowContext ctx, Order order, String kind, Map<String, Object> payload, int n) {
        Map<String, Object> result = ctx.activity(kind + "#" + n, RETRY, () -> reject(order, kind, payload));
        ctx.log(kind + " handled: lines back to sourcing, node penalised, re-sourced (FR-SRC-07)", result);
        String status = OrderWorkflowSupport.str(result.get("status"));
        if ("SOURCED".equals(status)) {
            ctx.vars().put("cycle", OrderWorkflowSupport.intOf(ctx.vars().get("cycle"), 0) + 1);
            ctx.vars().remove("reserveRetries");
            ctx.vars().put("plan", result.get("resource"));
            return Next.goTo("reserve");
        }
        if ("REQUIRES_APPROVAL".equals(status)) return Next.goTo("source");
        if ("NO_FEASIBLE_NODE".equals(status)) return Next.goTo("backorder");
        return Next.goTo("await_fulfilment");
    }

    /** Store reject / short pick: transitions, reservation release at the node, re-source, classified exception. */
    Map<String, Object> reject(Order order, String kind, Map<String, Object> payload) {
        FulfilmentGroup group = resolveGroup(order, payload);
        String nodeId = group != null ? group.nodeId() : OrderWorkflowSupport.str(payload.get("nodeId"));
        Map<String, Integer> affected = affectedLines(order, group, payload);
        String trigger = "event." + kind;
        List<Map<String, Object>> transitions = new ArrayList<>();
        Set<String> skus = new LinkedHashSet<>();
        for (var e : affected.entrySet()) {
            OrderLine line = order.line(e.getKey()).orElse(null);
            if (line == null) continue;
            Integer qty = payloadLines(payload).isEmpty() ? null : e.getValue();
            transitions.add(s.fireLine(order.id(), line.lineId(), trigger, qty, Map.of()));
            skus.add(line.sku());
        }
        List<String> released = s.releaseReservationsAt(order.id(), nodeId, skus, kind);
        Map<String, Object> resource;
        try { resource = planSummary(sourcing.resource(order.id(), nodeId, kind, OrderWorkflowSupport.ACTOR)); }
        catch (ConflictException e) { resource = Map.of("status", "NO_ACTIVE_GROUP", "error", String.valueOf(e.getMessage())); }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("nodeId", String.valueOf(nodeId));
        details.put("lines", affected);
        details.put("reason", String.valueOf(payload.getOrDefault("reason", kind)));
        details.put("resourceStatus", resource.get("status"));
        String message = Signals.SHORT_PICK.equals(kind) ? "Short pick: inventory shortage at " + nodeId : "Store rejected the release at " + nodeId;
        Map<String, Object> exc = s.raise("wms", kind, message + " for lines " + affected.keySet(), order.id(), affected.size() == 1 ? affected.keySet().iterator().next() : null, nodeId, details, null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("transitions", transitions);
        out.put("reservationsReleased", released);
        out.put("resource", resource);
        out.put("status", resource.get("status"));
        out.put("exception", exc);
        return out;
    }

    private Next onPickProgress(WorkflowContext ctx, Order order, String kind, Map<String, Object> payload, int n) {
        List<Map<String, Object>> moved = ctx.activity(kind + "#" + n, RETRY, () -> {
            FulfilmentGroup group = resolveGroup(order, payload);
            Map<String, Integer> explicit = payloadLines(payload);
            List<Map<String, Object>> out = new ArrayList<>();
            for (var e : affectedLines(order, group, payload).entrySet()) {
                if (Signals.PACKED.equals(kind)) {
                    int fromReleased = Math.min(e.getValue(), s.qtyIn(order, e.getKey(), "released"));
                    if (fromReleased > 0) out.add(s.fireLine(order.id(), e.getKey(), "event.pick_started", fromReleased, Map.of()));
                }
                out.add(s.fireLine(order.id(), e.getKey(), "event." + kind, explicit.isEmpty() ? null : e.getValue(), Map.of()));
            }
            return out;
        });
        ctx.log("WMS progress: " + kind, Map.of("transitions", moved));
        return Next.goTo("await_fulfilment");
    }

    /** A CS modification arrived while waiting: finish when everything is cancelled, else keep waiting. */
    Next onModified(WorkflowContext ctx, Order order, Map<String, Object> payload, int n, String againStep) {
        boolean allCancelled = s.allLinesIn(order, Set.of("cancelled"));
        ctx.log("Order modified (FR-CS-02)", Map.of("payload", payload, "allCancelled", allCancelled));
        if (!allCancelled) return Next.goTo(againStep);
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

    // ------------------------------------------------------------------ delivery (UC-05)

    Next awaitDelivery(WorkflowContext ctx) {
        Duration timeout = OrchestrationConfig.duration(config, OrchestrationConfigKeys.DELIVERY_TIMEOUT, props.deliveryTimeout());
        return Next.waitSignal(DELIVERY_SIGNALS, timeout, "delivery_timeout", "on_delivery_event");
    }

    Next onDeliveryEvent(WorkflowContext ctx) {
        Signal sig = ctx.signal().orElseThrow();
        int n = OrderWorkflowSupport.intOf(ctx.vars().get("signals"), 0) + 1;
        ctx.vars().put("signals", n);
        Map<String, Object> payload = jsonPayload(sig);
        Order order = order(ctx);
        switch (sig.name()) {
            case Signals.DELIVERED -> {
                Map<String, Object> result = ctx.activity("delivered#" + n, RETRY, () -> deliver(order, payload));
                ctx.log("Delivery recorded", result);
                if (Boolean.TRUE.equals(result.get("allDelivered"))) return Next.complete(Map.of("status", "delivered", "shipments", result.getOrDefault("shipments", List.of())));
                return Next.goTo("await_delivery");
            }
            case Signals.CARRIER_EXCEPTION, Signals.RETURNED_TO_SENDER -> {
                Map<String, Object> result = ctx.activity(sig.name() + "#" + n, RETRY, () -> {
                    List<String> touched = new ArrayList<>();
                    for (ShipmentRecord sh : shipmentsFor(order, payload)) { shipments.applyCarrierEvent(sh.id(), Signals.CARRIER_EXCEPTION.equals(sig.name()) ? "exception" : "returned_to_sender", OrderWorkflowSupport.ACTOR); touched.add(sh.id()); }
                    Map<String, Object> details = new LinkedHashMap<>(payload);
                    details.put("shipments", touched);
                    Map<String, Object> exc = s.raise("carrier", sig.name(), Signals.CARRIER_EXCEPTION.equals(sig.name()) ? "Carrier reported an exception on " + payload.getOrDefault("tracking", "shipment") : "Parcel returned to sender",
                        order.id(), null, null, details, Signals.CARRIER_EXCEPTION.equals(sig.name()) ? "carrier_delay" : null);
                    return Map.of("shipments", touched, "exception", exc);
                });
                ctx.log("Carrier event " + sig.name() + "; exception raised, still waiting for delivery", result);
                return Next.goTo("await_delivery");
            }
            case Signals.ORDER_MODIFIED -> { return onModified(ctx, order, payload, n, "await_delivery"); }
            default -> { return Next.goTo("await_delivery"); }
        }
    }

    Map<String, Object> deliver(Order order, Map<String, Object> payload) {
        List<Map<String, Object>> transitions = new ArrayList<>();
        List<String> delivered = new ArrayList<>();
        for (ShipmentRecord sh : shipmentsFor(order, payload)) {
            if ("delivered".equals(sh.status())) continue;
            shipments.markDelivered(sh.id(), OrderWorkflowSupport.ACTOR);
            for (ShipmentRecord.Line l : sh.lines()) transitions.add(s.fireLine(order.id(), l.lineId(), "event.delivered", l.qty(), Map.of()));
            delivered.add(sh.id());
        }
        if (delivered.isEmpty()) {
            // No shipment matched (e.g. shipped before shipments were tracked): deliver whatever is shipped.
            for (OrderLine l : order.lines()) if (s.qtyIn(order, l.lineId(), "shipped") > 0) transitions.add(s.fireLine(order.id(), l.lineId(), "event.delivered", null, Map.of()));
        }
        Order fresh = s.orders().get(order.id());
        boolean all = s.allLinesIn(fresh, Set.of("delivered", "cancelled"));
        Set<String> groups = new LinkedHashSet<>();
        if (all) for (FulfilmentGroup g : fresh.fulfilmentGroups()) if ("shipped".equalsIgnoreCase(g.status()) || "released".equalsIgnoreCase(g.status())) groups.add(g.id());
        if (!groups.isEmpty()) s.markGroups(fresh.id(), groups, "delivered", null);
        Map<String, Object> evt = new LinkedHashMap<>();
        evt.put("shipments", delivered);
        evt.put("tracking", String.valueOf(payload.getOrDefault("tracking", "")));
        evt.put("allDelivered", all);
        // On-time/late/damaged, computed here against each group's own promise date rather than left for a consumer
        // to guess (SOO's OutcomeObserver, and the reward's otd term, read these fields directly and otherwise have
        // no basis to compute them). A group with no promiseDate (not every policy sets one) doesn't penalize.
        java.time.LocalDate today = java.time.LocalDate.now();
        boolean onTime = true;
        long lateByHours = 0;
        for (FulfilmentGroup g : fresh.fulfilmentGroups()) {
            if (g.promiseDate() == null || !"delivered".equalsIgnoreCase(g.status())) continue;
            if (today.isAfter(g.promiseDate())) {
                onTime = false;
                lateByHours = Math.max(lateByHours, java.time.temporal.ChronoUnit.HOURS.between(g.promiseDate().atStartOfDay(), today.atStartOfDay()));
            }
        }
        evt.put("onTime", onTime);
        evt.put("lateByHours", lateByHours);
        evt.put("damaged", Boolean.TRUE.equals(payload.get("damaged")));
        evt.put("lost", Boolean.TRUE.equals(payload.get("lost")));
        s.appendOrderEvent(order.id(), "order.delivered", evt);
        if (all) notifier.notify(order.id(), "email", "delivered", "Your order was delivered", "All items of order " + order.id() + " were delivered", Map.of());
        Map<String, Object> out = new LinkedHashMap<>(evt);
        out.put("transitions", transitions);
        return out;
    }

    /** Shipments a carrier payload refers to: by id, else by tracking, else every undelivered shipment of the order. */
    private List<ShipmentRecord> shipmentsFor(Order order, Map<String, Object> payload) {
        String id = OrderWorkflowSupport.str(payload.get("shipmentId"));
        if (id != null) return shipments.find(id).map(List::of).orElse(List.of());
        String tracking = OrderWorkflowSupport.str(payload.get("tracking"));
        List<ShipmentRecord> byTracking = tracking == null ? List.of() : shipments.byTracking(tracking).stream().filter(sh -> sh.orderId().equals(order.id())).toList();
        if (!byTracking.isEmpty()) return byTracking;
        return shipments.forOrder(order.id()).stream().filter(sh -> !"delivered".equals(sh.status())).toList();
    }

    Next deliveryTimeout(WorkflowContext ctx) {
        Order order = order(ctx);
        Map<String, Object> exc = ctx.activity("delivery_timeout", RETRY, () -> s.raise("carrier", "delivery_unconfirmed", "No delivery scan within the delivery window", order.id(), null, null,
            Map.of("shipments", shipments.forOrder(order.id()).stream().map(ShipmentRecord::id).toList()), "carrier_delay"));
        ctx.log("Delivery never confirmed by the carrier; closing as delivery_unconfirmed", exc);
        return Next.complete(Map.of("status", "delivery_unconfirmed", "exception", exc));
    }
}
