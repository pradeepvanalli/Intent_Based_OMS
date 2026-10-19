package ai.intentoms.orchestration.integration;

import ai.intentoms.inventory.model.InventoryPosition;
import ai.intentoms.inventory.service.InventoryService;
import ai.intentoms.orchestration.shipment.ShipmentRecord;
import ai.intentoms.orchestration.shipment.ShipmentService;
import ai.intentoms.orchestration.workflow.OrderEventSubscriber;
import ai.intentoms.orchestration.workflow.Signals;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.service.OrderService;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.platform.time.TimeSource;
import ai.intentoms.returns.service.ReturnService;
import ai.intentoms.workflow.model.WorkflowEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns WMS, carrier and inventory events into workflow signals, shipment transitions, return call-backs and inventory
 * feeds (FR-WF-05, FR-INV-04, UC-05/UC-06/UC-08). Used by the webhook controllers and by the demo seeder; every method is
 * idempotent on the event id through {@link WebhookIdempotency}.
 */
@Service
public class InboundEventService {
    private static final Logger log = LoggerFactory.getLogger(InboundEventService.class);
    private final OrderService orders;
    private final OrderEventSubscriber orderWorkflows;
    private final WorkflowEngine engine;
    private final ReturnService returns;
    private final ShipmentService shipments;
    private final InventoryService inventory;
    private final WebhookIdempotency idempotency;
    private final AuthorizationService authz;
    private final TimeSource time;
    private final Metrics metrics;
    private final JdbcClient jdbc;

    public InboundEventService(OrderService orders, OrderEventSubscriber orderWorkflows, WorkflowEngine engine, ReturnService returns, ShipmentService shipments,
                               InventoryService inventory, WebhookIdempotency idempotency, AuthorizationService authz, TimeSource time, Metrics metrics, JdbcClient jdbc) {
        this.orders = orders;
        this.orderWorkflows = orderWorkflows;
        this.engine = engine;
        this.returns = returns;
        this.shipments = shipments;
        this.inventory = inventory;
        this.idempotency = idempotency;
        this.authz = authz;
        this.time = time;
        this.metrics = metrics;
        this.jdbc = jdbc;
    }

    /** {@code POST /webhooks/wms/{nodeId}}: order events become signals of {@code order.<orderType>}; return receipts call the returns module. */
    public Map<String, Object> wms(String nodeId, Map<String, Object> body) {
        WebhookPayloads.WmsEvent ev = WebhookPayloads.wms(nodeId, body);
        return idempotency.once("webhooks.wms", ev.eventId(), () -> {
            metrics.count("oms.orchestration.webhook", "source", "wms", "type", ev.type());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("accepted", true);
            out.put("type", ev.type());
            out.put("nodeId", nodeId);
            if (ev.forReturn()) {
                Actor actor = actor();
                if ("received".equals(ev.type())) returns.receive(ev.rmaId(), nodeId, actor);
                else returns.inspect(ev.rmaId(), ev.condition() == null ? "new" : ev.condition(), ev.note(), actor);
                out.put("rmaId", ev.rmaId());
                out.put("status", returns.getRma(ev.rmaId()).status());
                return out;
            }
            Order order = orders.get(ev.orderId());
            String type = orderWorkflows.workflowType(order);
            engine.signal(type, order.id(), ev.type(), ev.signalPayload(), ev.eventId());
            out.put("orderId", order.id());
            out.put("workflowType", type);
            out.put("signal", ev.type());
            engine.find(type, order.id()).ifPresent(w -> out.put("workflowId", w.id()));
            return out;
        });
    }

    /** {@code POST /webhooks/carrier}: shipment transitions plus the order-workflow signals delivered / carrier_exception / returned_to_sender. */
    public Map<String, Object> carrier(Map<String, Object> body) {
        WebhookPayloads.CarrierEvent ev = WebhookPayloads.carrier(body);
        return idempotency.once("webhooks.carrier", ev.eventId(), () -> {
            metrics.count("oms.orchestration.webhook", "source", "carrier", "type", ev.type());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("accepted", true);
            out.put("type", ev.type());
            if ("return_scan".equals(ev.type())) {
                returns.recordScan(ev.rmaId(), actor());
                out.put("rmaId", ev.rmaId());
                out.put("status", returns.getRma(ev.rmaId()).status());
                return out;
            }
            List<ShipmentRecord> targets = resolveShipments(ev);
            List<String> touched = new ArrayList<>();
            List<Map<String, Object>> touchedDetail = new ArrayList<>();
            Set<String> orderIds = new LinkedHashSet<>();
            for (ShipmentRecord sh : targets) {
                shipments.applyCarrierEvent(sh.id(), ev.type(), actor());
                touched.add(sh.id());
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("shipmentId", sh.id());
                d.put("orderId", sh.orderId());
                if (sh.fulfilmentGroupId() != null) d.put("fulfilmentGroupId", sh.fulfilmentGroupId());
                touchedDetail.add(d);
                orderIds.add(sh.orderId());
            }
            if (ev.orderId() != null) orderIds.add(ev.orderId());
            out.put("shipments", touched);
            String signal = ev.signal();
            List<String> signalled = new ArrayList<>();
            List<String> ignored = new ArrayList<>();
            if (signal != null) {
                for (String orderId : orderIds) {
                    Order order;
                    try { order = orders.get(orderId); }
                    catch (NotFoundException e) { log.warn("Carrier event {} for unknown order {}", ev.type(), orderId); continue; }
                    List<Map<String, Object>> forOrder = touchedDetail.stream().filter(d -> orderId.equals(d.get("orderId"))).toList();
                    if (namesParcel(ev) && forOrder.isEmpty() && !shipments.forOrder(orderId).isEmpty()) {
                        log.warn("Carrier event {} {} for order {} names a parcel the order does not have (tracking {}, shipment {}); ignored",
                            ev.type(), ev.eventId(), orderId, ev.tracking(), ev.shipmentId());
                        ignored.add(orderId);
                        continue;
                    }
                    if (engine.signalIfPresent(orderWorkflows.workflowType(order), orderId, signal, ev.signalPayload(forOrder), ev.eventId() + ":" + orderId)) {
                        signalled.add(orderId);
                    } else {
                        log.warn("Carrier event {} for order {} without a workflow", ev.type(), orderId);
                    }
                }
            }
            out.put("signal", signal == null ? "" : signal);
            out.put("orders", signalled);
            if (!ignored.isEmpty()) out.put("ignored", Map.of("orders", ignored, "reason", "no parcel of the order matches the scan"));
            return out;
        });
    }

    private List<ShipmentRecord> resolveShipments(WebhookPayloads.CarrierEvent ev) {
        if (ev.shipmentId() != null) return shipments.find(ev.shipmentId()).map(List::of).orElse(List.of());
        List<ShipmentRecord> byTracking = shipments.byTracking(ev.tracking());
        if (ev.orderId() != null) byTracking = byTracking.stream().filter(s -> s.orderId().equals(ev.orderId())).toList();
        if (!byTracking.isEmpty() || ev.orderId() == null) return byTracking;
        // A scan that names a tracking number speaks for that parcel only; matching none of the order's parcels it
        // moves nothing (audit A5). Only a scan that identifies no parcel at all means the whole order.
        if (namesParcel(ev)) return List.of();
        return shipments.forOrder(ev.orderId()).stream().filter(s -> !"delivered".equals(s.status())).toList();
    }

    private static boolean namesParcel(WebhookPayloads.CarrierEvent ev) {
        return ev.shipmentId() != null || (ev.tracking() != null && !ev.tracking().isBlank());
    }

    /** {@code POST /webhooks/inventory/{nodeId}}: positions feed plus {@code inventory_available} for backordered lines of the fed skus. */
    public Map<String, Object> inventory(String nodeId, Object body) {
        Map<String, Object> map = body instanceof Map<?, ?> m ? castMap(m) : Map.of();
        String eventId = WebhookPayloads.eventId(map, "inventory");
        String source = map.get("source") == null ? "wms:" + nodeId : String.valueOf(map.get("source"));
        List<InventoryPosition> positions = WebhookPayloads.positions(nodeId, body, time.now());
        return idempotency.once("webhooks.inventory", eventId, () -> {
            metrics.count("oms.orchestration.webhook", "source", "inventory", "type", "feed");
            inventory.upsert(positions, source);
            Set<String> skus = new LinkedHashSet<>();
            for (InventoryPosition p : positions) if (p.isSupply() && p.qty() > 0) skus.add(p.sku());
            List<String> woken = new ArrayList<>();
            for (String orderId : backorderedOrders(skus)) {
                Order order;
                try { order = orders.get(orderId); }
                catch (NotFoundException e) { log.debug("inventory_available for unknown order {}", orderId); continue; }
                if (engine.signalIfPresent(orderWorkflows.workflowType(order), orderId, Signals.INVENTORY_AVAILABLE,
                        Map.of("nodeId", nodeId, "skus", new ArrayList<>(skus), "eventId", eventId), eventId + ":" + orderId)) {
                    woken.add(orderId);
                } else {
                    log.debug("inventory_available not delivered to {}: no workflow", orderId);
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("accepted", true);
            out.put("positions", positions.size());
            out.put("backorderedOrdersWoken", woken);
            return out;
        });
    }

    private List<String> backorderedOrders(Set<String> skus) {
        if (skus.isEmpty()) return List.of();
        return jdbc.sql("select distinct order_id from order_line where tenant_id = :t and status = 'backordered' and sku in (:skus) order by order_id")
            .param("t", TenantContext.require()).param("skus", new ArrayList<>(skus)).query(String.class).list();
    }

    /** Ops: push a named signal to an order's workflow ({@code POST /api/v1/orders/{id}/signals}). */
    public Map<String, Object> signal(String orderId, String name, Map<String, Object> payload, String idempotencyKey) {
        Order order = orders.get(orderId);
        String type = orderWorkflows.workflowType(order);
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? "ops:" + name + ":" + ai.intentoms.platform.ids.Ids.id() : idempotencyKey;
        engine.signal(type, orderId, name, payload == null ? Map.of() : payload, key);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", orderId);
        out.put("workflowType", type);
        out.put("signal", name);
        out.put("idempotencyKey", key);
        engine.find(type, orderId).ifPresent(w -> { out.put("workflowId", w.id()); out.put("status", w.status()); out.put("currentStep", w.currentStep()); });
        return out;
    }

    private Actor actor() { return Actor.parse(authz.actorRef()); }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) { return (Map<String, Object>) m; }
}
