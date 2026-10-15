package ai.intentoms.integrations.graphql;

import ai.intentoms.inventory.service.InventoryService;
import ai.intentoms.order.capture.CaptureRequest;
import ai.intentoms.order.model.MarginSnapshot;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.model.OrderQuery;
import ai.intentoms.order.model.OrderSummary;
import ai.intentoms.order.model.ShipmentSummary;
import ai.intentoms.order.modification.Modification;
import ai.intentoms.order.modification.OrderModificationService;
import ai.intentoms.order.service.OrderService;
import ai.intentoms.order.service.ShipmentProvider;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.jdbc.Page;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.Principal;
import ai.intentoms.returns.model.ReturnRecord;
import ai.intentoms.returns.model.ReturnRequest;
import ai.intentoms.returns.model.ReturnSummary;
import ai.intentoms.returns.model.ReturnRma;
import ai.intentoms.returns.service.ReturnService;
import ai.intentoms.sourcing.PromiseService;
import ai.intentoms.sourcing.model.Promise;
import ai.intentoms.sourcing.model.PromiseRequest;
import ai.intentoms.statemachine.StateMachine;
import ai.intentoms.statemachine.model.EntityStatus;
import ai.intentoms.statemachine.model.QuantityBucket;
import com.fasterxml.jackson.core.type.TypeReference;
import graphql.schema.DataFetchingEnvironment;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GraphQL resolvers for capture and queries (FR-CAP-01; schema in {@code graphql/schema.graphqls}). Thin: every
 * operation delegates to the same services the REST controllers use, so validation (FR-CAP-05), idempotency (FR-CAP-04),
 * the ledger and the workflows behave identically over both transports. Permissions are enforced here with
 * {@link AuthorizationService#require} because {@code @RequiresPermission} is a controller-method aspect that GraphQL
 * data fetchers bypass; the HTTP chain already requires an authenticated principal on {@code POST /graphql}.
 */
@Controller
public class OmsGraphQlController {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private final OrderService orders;
    private final OrderModificationService modifications;
    private final PromiseService promises;
    private final InventoryService inventory;
    private final ReturnService returns;
    private final StateMachine machine;
    private final ShipmentProvider shipments;
    private final AuthorizationService authz;
    private final Metrics metrics;

    public OmsGraphQlController(OrderService orders, OrderModificationService modifications, PromiseService promises, InventoryService inventory, ReturnService returns,
                                StateMachine machine, ShipmentProvider shipments, AuthorizationService authz, Metrics metrics) {
        this.orders = orders;
        this.modifications = modifications;
        this.promises = promises;
        this.inventory = inventory;
        this.returns = returns;
        this.machine = machine;
        this.shipments = shipments;
        this.authz = authz;
        this.metrics = metrics;
    }

    // ---- Query -------------------------------------------------------------------------------------------------------

    @QueryMapping
    public Order order(@Argument String id) {
        authz.require(Permissions.ORDER_READ);
        metrics.count("oms.integrations.graphql", "op", "order");
        return orders.get(id);
    }

    @QueryMapping
    public Order orderByChannelRef(@Argument String channel, @Argument String channelOrderId) {
        authz.require(Permissions.ORDER_READ);
        metrics.count("oms.integrations.graphql", "op", "orderByChannelRef");
        return orders.byChannelRef(channel, channelOrderId).orElse(null);
    }

    @QueryMapping
    public Page<OrderSummary> orders(@Argument Map<String, Object> filter, @Argument Integer offset, @Argument Integer limit) {
        authz.require(Permissions.ORDER_READ);
        metrics.count("oms.integrations.graphql", "op", "orders");
        Map<String, Object> f = filter == null ? Map.of() : filter;
        List<String> statuses = f.get("statuses") instanceof List<?> l ? l.stream().map(String::valueOf).toList() : null;
        OrderQuery q = new OrderQuery(statuses, str(f.get("channel")), str(f.get("orderType")), str(f.get("customerRef")), str(f.get("nodeId")),
            instant(f.get("placedFrom")), instant(f.get("placedTo")), str(f.get("text")), bool(f.get("agentOrders")), bool(f.get("belowFloor")),
            offset == null ? 0 : offset, limit == null ? 50 : limit);
        return orders.search(q);
    }

    @QueryMapping
    public Promise promise(@Argument Map<String, Object> input) {
        authz.require(Permissions.PROMISE_READ);
        metrics.count("oms.integrations.graphql", "op", "promise");
        PromiseRequest req = Json.convert(input, PromiseRequest.class);
        if (req.lines() == null || req.lines().isEmpty()) throw new ValidationException("promise.lines must not be empty");
        return promises.promise(req);
    }

    @QueryMapping
    public List<Map<String, Object>> inventory(@Argument List<String> skus) {
        authz.require(Permissions.INVENTORY_READ);
        metrics.count("oms.integrations.graphql", "op", "inventory");
        if (skus == null || skus.isEmpty()) return List.of();
        Map<String, Integer> atp = inventory.networkAtp(new ArrayList<>(new java.util.LinkedHashSet<>(skus)));
        List<Map<String, Object>> out = new ArrayList<>();
        for (String sku : new java.util.LinkedHashSet<>(skus)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sku", sku);
            row.put("available", atp.getOrDefault(sku, 0));
            out.add(row);
        }
        return out;
    }

    @QueryMapping
    public List<ReturnSummary> returns(@Argument String orderId) {
        authz.require(Permissions.ORDER_READ);
        metrics.count("oms.integrations.graphql", "op", "returns");
        return returns.forOrder(orderId);
    }

    // ---- Mutation ----------------------------------------------------------------------------------------------------

    @MutationMapping
    public OrderService.CaptureResult captureOrder(@Argument Map<String, Object> input, @Argument String idempotencyKey) {
        authz.require(Permissions.ORDER_CREATE);
        metrics.count("oms.integrations.graphql", "op", "captureOrder");
        CaptureRequest req = Json.convert(input, CaptureRequest.class);
        return orders.captureDetailed(req, idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim());
    }

    @MutationMapping
    public Order modifyOrder(@Argument String orderId, @Argument Map<String, Object> modification, @Argument String reason) {
        authz.require(Permissions.ORDER_MODIFY);
        metrics.count("oms.integrations.graphql", "op", "modifyOrder");
        Modification m = toModification(modification);
        return modifications.apply(orderId, m, reason, actor());
    }

    @MutationMapping
    public Order cancelOrder(@Argument String orderId, @Argument String reason) {
        authz.require(Permissions.ORDER_CANCEL);
        metrics.count("oms.integrations.graphql", "op", "cancelOrder");
        return modifications.apply(orderId, new Modification.CancelOrder(), reason, actor());
    }

    @MutationMapping
    public ReturnRecord createReturn(@Argument Map<String, Object> input) {
        authz.require(Permissions.RETURN_CREATE);
        metrics.count("oms.integrations.graphql", "op", "createReturn");
        ReturnRequest req = Json.convert(input, ReturnRequest.class);
        ReturnRma rma = returns.create(req, actor());
        return returns.get(rma.rmaId());
    }

    // ---- Order field resolvers -----------------------------------------------------------------------------------------

    /** Lines with their quantity buckets, looked up in one call only when the client selected {@code quantities}. */
    @SchemaMapping(typeName = "Order", field = "lines")
    public List<GqlOrderLine> lines(Order order, DataFetchingEnvironment env) {
        boolean wantQuantities = env.getSelectionSet().contains("quantities");
        Map<String, List<QuantityBucket>> buckets = new HashMap<>();
        if (wantQuantities && !order.lines().isEmpty()) {
            List<String> ids = order.lines().stream().map(l -> order.id() + ":" + l.lineId()).toList();
            for (EntityStatus s : safeStatuses("order_line", ids)) buckets.put(s.entityId(), s.quantities());
        }
        List<GqlOrderLine> out = new ArrayList<>(order.lines().size());
        for (var l : order.lines()) out.add(GqlOrderLine.of(order.id(), l, buckets.get(order.id() + ":" + l.lineId())));
        return out;
    }

    @SchemaMapping(typeName = "Order", field = "header")
    public EntityStatus header(Order order) {
        try { return machine.status("order", order.id()); } catch (NotFoundException e) { return null; }
    }

    @SchemaMapping(typeName = "Order", field = "lineStatuses")
    public List<EntityStatus> lineStatuses(Order order) {
        if (order.lines().isEmpty()) return List.of();
        return safeStatuses("order_line", order.lines().stream().map(l -> order.id() + ":" + l.lineId()).toList());
    }

    @SchemaMapping(typeName = "Order", field = "shipments")
    public List<ShipmentSummary> shipments(Order order) { return shipments.shipmentsFor(order.id()); }

    @SchemaMapping(typeName = "Order", field = "returns")
    public List<ReturnSummary> orderReturns(Order order) { return returns.forOrder(order.id()); }

    /** Margin is internal (FR-FIN-04): hidden from agent principals (FR-AGT-*), visible to users and system callers. */
    @SchemaMapping(typeName = "Order", field = "margin")
    public MarginSnapshot margin(Order order) {
        return authz.currentPrincipal().map(Principal::isAgent).orElse(true) ? null : order.margin();
    }

    // ---- helpers -------------------------------------------------------------------------------------------------------

    private List<EntityStatus> safeStatuses(String type, List<String> ids) {
        try { return machine.statuses(type, ids); } catch (NotFoundException e) { return List.of(); }
    }

    /** {@code ModificationInput} → the sealed {@link Modification}: the enum lands in {@code type}, the rest is flat like REST. */
    static Modification toModification(Map<String, Object> in) {
        if (in == null || in.get("type") == null) throw new ValidationException("modification.type is required");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", String.valueOf(in.get("type")));
        if (in.get("lineId") != null) m.put("lineId", in.get("lineId"));
        if (in.get("qty") != null) m.put("qty", in.get("qty"));
        if (in.get("newSku") != null) m.put("newSku", in.get("newSku"));
        if (in.get("shipMethod") != null) m.put("shipMethod", in.get("shipMethod"));
        if (in.get("address") instanceof Map<?, ?> a) {
            Map<String, Object> addr = new LinkedHashMap<>(Json.convert(a, MAP));
            addr.putIfAbsent("type", "ship_to");
            m.put("address", addr);
        }
        try { return Json.convert(m, Modification.class); } catch (IllegalArgumentException e) { throw new ValidationException("Invalid modification: " + e.getMessage()); }
    }

    private Actor actor() { return Actor.parse(authz.actorRef()); }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
    private static Boolean bool(Object o) { return o instanceof Boolean b ? b : o == null ? null : Boolean.parseBoolean(o.toString()); }
    private static Instant instant(Object o) { return o instanceof Instant i ? i : o == null ? null : Instant.parse(o.toString()); }
}
