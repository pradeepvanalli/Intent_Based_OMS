package ai.intentoms.agent.ucp;

import ai.intentoms.agent.CustomerSafeOrderView;
import ai.intentoms.agent.registry.AgentIdentity;
import ai.intentoms.agent.registry.AgentRegistry;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.service.OrderService;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.ledger.EventSubscriber;
import ai.intentoms.returns.model.ReturnEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Lifecycle webhooks for agent-placed orders (FR-AGT-02): reacts to header-level {@code order.transitioned} into
 * shipped/delivered (partial roll-ups included, flagged {@code partial}), {@code return.requested} and {@code return.refunded},
 * and posts {@code {ucp_version, type, order_id, agent_id, event_id, occurred_at, data}} to the placing agent's callback
 * URL. Idempotent per (event, agent). Runs after commit through the outbox relay, tenant already bound.
 */
@Component
public class UcpWebhookSubscriber implements EventSubscriber {
    private static final Logger log = LoggerFactory.getLogger(UcpWebhookSubscriber.class);
    private static final Set<String> SHIPPED = Set.of("shipped", "partially_shipped");
    private static final Set<String> DELIVERED = Set.of("delivered", "partially_delivered");

    private final OrderService orders;
    private final AgentRegistry registry;
    private final UcpWebhookSender sender;

    public UcpWebhookSubscriber(OrderService orders, AgentRegistry registry, UcpWebhookSender sender) {
        this.orders = orders;
        this.registry = registry;
        this.sender = sender;
    }

    @Override public List<String> eventTypePrefixes() { return List.of("order.transitioned", ReturnEvents.REQUESTED, ReturnEvents.REFUNDED); }

    @Override
    public void on(Event e) {
        Optional<Mapped> mapped = map(e);
        if (mapped.isEmpty()) return;
        Mapped m = mapped.get();
        Order order;
        try { order = orders.get(m.orderId()); } catch (NotFoundException ex) { return; }
        if (order.provenance() == null || order.provenance().agentId() == null) return;
        Optional<AgentIdentity> agent = registry.find(order.provenance().agentId());
        if (agent.isEmpty() || !agent.get().hasCallback() || !agent.get().active()) return;
        AgentIdentity a = agent.get();
        if (sender.alreadyDelivered(e.id(), a.id())) return;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ucp_version", UcpVersions.DEFAULT);
        body.put("type", m.type());
        body.put("order_id", order.id());
        body.put("client_order_id", order.channelOrderId());
        body.put("agent_id", a.id());
        body.put("mandate_id", order.provenance().mandateId());
        body.put("event_id", e.id());
        body.put("occurred_at", e.occurredAt().toString());
        body.put("data", m.data());
        sender.send(a.id(), registry.hmacSecret(a.id()), a.callbackUrl(), e.id(), m.type(), order.id(), body);
        log.debug("UCP webhook {} for order {} sent to agent {}", m.type(), order.id(), a.id());
    }

    record Mapped(String type, String orderId, Map<String, Object> data) {}

    /** Event → webhook type; empty when the event is not a lifecycle milestone. Package-visible for tests. */
    static Optional<Mapped> map(Event e) {
        Map<String, Object> p = e.payload() == null ? Map.of() : e.payload();
        if ("order.transitioned".equals(e.type())) {
            if (!"order".equals(p.get("entityType"))) return Optional.empty();
            String to = String.valueOf(p.get("to"));
            String type = SHIPPED.contains(to) ? "order.shipped" : DELIVERED.contains(to) ? "order.delivered" : null;
            if (type == null) return Optional.empty();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", to);
            data.put("partial", to.startsWith("partially_"));
            data.put("from", p.get("from"));
            data.put("transition_id", p.get("transitionId"));
            return Optional.of(new Mapped(type, e.aggregateId(), data));
        }
        if (ReturnEvents.REQUESTED.equals(e.type()) || ReturnEvents.REFUNDED.equals(e.type())) {
            Object orderId = p.get("orderId");
            if (orderId == null) return Optional.empty();
            Map<String, Object> data = new LinkedHashMap<>(CustomerSafeOrderView.redact(p));
            data.put("return_id", e.aggregateId());
            return Optional.of(new Mapped(ReturnEvents.REQUESTED.equals(e.type()) ? "return.created" : "refund.issued", orderId.toString(), data));
        }
        return Optional.empty();
    }
}
