package ai.intentoms.orchestration.workflow;

import ai.intentoms.order.model.Order;
import ai.intentoms.order.service.OrderService;
import ai.intentoms.order.service.OrderTypeRegistry;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.ledger.EventSubscriber;
import ai.intentoms.workflow.model.WorkflowEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Order-ledger facts that wake the order workflow (FR-WF-05, FR-SM-07): {@code hold.released} on an order becomes the
 * {@code hold_released} signal so a release blocked by a compliance / payment / address hold resumes as soon as the hold
 * is lifted through the holds API or a bulk release. Idempotent on the event id.
 */
@Component
public class OrderEventSubscriber implements EventSubscriber {
    private static final Logger log = LoggerFactory.getLogger(OrderEventSubscriber.class);
    private final WorkflowEngine engine;
    private final OrderService orders;
    private final OrderTypeRegistry orderTypes;

    public OrderEventSubscriber(WorkflowEngine engine, OrderService orders, OrderTypeRegistry orderTypes) {
        this.engine = engine;
        this.orders = orders;
        this.orderTypes = orderTypes;
    }

    @Override public List<String> eventTypePrefixes() { return List.of("hold.released"); }

    @Override
    public void on(Event e) {
        if (!"order".equals(e.aggregateType())) return;
        Order order;
        try { order = orders.get(e.aggregateId()); }
        catch (NotFoundException missing) { log.debug("hold.released on unknown order {}", e.aggregateId()); return; }
        boolean delivered = engine.signalIfPresent(workflowType(order), order.id(), Signals.HOLD_RELEASED,
            Map.of("holdId", String.valueOf(e.payload().get("holdId")), "type", String.valueOf(e.payload().get("type"))), e.id());
        if (!delivered) log.debug("hold.released on {} has no order workflow", e.aggregateId());
    }

    /** Workflow type of an order: the order type's binding, else {@code order.<type>}. */
    public String workflowType(Order order) {
        try { return orderTypes.get(order.orderType()).workflowType(); } catch (NotFoundException e) { return "order." + order.orderType(); }
    }
}
