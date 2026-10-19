package ai.intentoms.orchestration.actions;

import ai.intentoms.monitoring.playbook.PlaybookContext;
import ai.intentoms.order.model.FulfilmentGroup;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.service.OrderService;
import ai.intentoms.order.service.OrderTypeRegistry;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.workflow.model.WorkflowEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Shared plumbing for the playbook actions (FR-MON-04): order lookup, workflow type resolution and signalling. */
@Component
public class ActionSupport {
    private static final Logger log = LoggerFactory.getLogger(ActionSupport.class);
    static final Set<String> INACTIVE_GROUP = Set.of("rejected", "cancelled", "shipped", "delivered", "picked_up");
    private final OrderService orders;
    private final OrderTypeRegistry orderTypes;
    private final WorkflowEngine engine;

    public ActionSupport(OrderService orders, OrderTypeRegistry orderTypes, WorkflowEngine engine) {
        this.orders = orders;
        this.orderTypes = orderTypes;
        this.engine = engine;
    }

    public OrderService orders() { return orders; }

    public Optional<Order> order(PlaybookContext ctx) {
        if (ctx.order().isPresent()) return ctx.order();
        if (ctx.orderId() == null) return Optional.empty();
        try { return Optional.of(orders.get(ctx.orderId())); } catch (NotFoundException e) { return Optional.empty(); }
    }

    public String workflowType(Order order) {
        try { return orderTypes.get(order.orderType()).workflowType(); } catch (NotFoundException e) { return "order." + order.orderType(); }
    }

    /** Signals the order's workflow; a missing instance is logged, never thrown. Returns whether it was delivered. */
    public boolean signal(Order order, String name, Map<String, Object> payload, String idempotencyKey) {
        if (engine.signalIfPresent(workflowType(order), order.id(), name, payload, idempotencyKey)) return true;
        log.debug("No workflow for order {}: signal {} dropped", order.id(), name);
        return false;
    }

    public WorkflowEngine engine() { return engine; }

    public static List<FulfilmentGroup> activeGroups(Order order, String nodeId) {
        return order.fulfilmentGroups().stream().filter(g -> g.status() == null || !INACTIVE_GROUP.contains(g.status().toLowerCase())).filter(g -> nodeId == null || nodeId.equals(g.nodeId())).toList();
    }

    public static String str(Map<String, Object> step, String key, String dflt) { Object v = step.get(key); return v == null ? dflt : String.valueOf(v); }

    public static double num(Object v, double dflt) {
        if (v instanceof Number n) return n.doubleValue();
        if (v == null) return dflt;
        try { return Double.parseDouble(v.toString()); } catch (NumberFormatException e) { return dflt; }
    }
}
