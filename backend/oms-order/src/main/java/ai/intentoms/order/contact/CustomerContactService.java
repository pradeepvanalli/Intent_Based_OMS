package ai.intentoms.order.contact;

import ai.intentoms.order.model.Order;
import ai.intentoms.order.service.OrderService;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.security.AuthorizationService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * OC-03 customer contacts: every time a customer gets in touch about an order, the order's ledger gets an event the
 * self-optimizing layer turns into a contact outcome (the reward's {@code cx} term) and the customer-preference
 * learner reads. "Where is my order" is {@code cs.wismo}, a complaint {@code cs.complaint}, anything else
 * {@code cs.contact}. Sources: the CS reply copilot when it classifies a message as WISMO, and integrations (help desk,
 * chat, IVR) through {@code POST /api/v1/orders/{id}/contacts}. A contact carrying an {@code externalId} (a help-desk
 * ticket id, a hash of the message) is recorded once, so retries and re-drafts are not counted twice.
 */
@Service
public class CustomerContactService {
    public static final String WISMO = "cs.wismo";
    public static final String COMPLAINT = "cs.complaint";
    public static final String OTHER = "cs.contact";

    private final OrderService orders;
    private final EventStore events;
    private final AuthorizationService authz;
    private final Metrics metrics;

    public CustomerContactService(OrderService orders, EventStore events, AuthorizationService authz, Metrics metrics) {
        this.orders = orders;
        this.events = events;
        this.authz = authz;
        this.metrics = metrics;
    }

    /**
     * @param reason     {@code wismo}, {@code complaint} or anything else
     * @param channel    email, chat, phone, sms, ...; defaults to {@code cs}
     * @param source     who reported it (cs_copilot, zendesk, ivr, ...)
     * @param externalId optional stable id that makes the call idempotent
     */
    public record Contact(String reason, String channel, String source, String externalId) {}

    public static String eventType(String reason) {
        String r = reason == null ? "" : reason.trim().toLowerCase(Locale.ROOT);
        return switch (r) {
            case "wismo", "where_is_my_order", "delivery_status" -> WISMO;
            case "complaint", "complain" -> COMPLAINT;
            default -> OTHER;
        };
    }

    public Map<String, Object> record(String orderId, Contact c) {
        Order order = orders.get(orderId);
        String type = eventType(c.reason());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", order.id());
        out.put("type", type);
        if (c.externalId() != null && !c.externalId().isBlank() && alreadyRecorded(order.id(), type, c.externalId())) {
            out.put("recorded", false);
            out.put("duplicate", true);
            return out;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", order.id());
        if (order.customerRef() != null) payload.put("customerRef", order.customerRef());
        payload.put("channel", c.channel() == null || c.channel().isBlank() ? "cs" : c.channel());
        payload.put("reason", c.reason() == null ? "other" : c.reason());
        payload.put("source", c.source() == null ? "api" : c.source());
        if (c.externalId() != null && !c.externalId().isBlank()) payload.put("externalId", c.externalId());
        Actor actor = authz.currentPrincipal().isPresent() ? Actor.parse(authz.actorRef()) : Actor.system("customer-contact");
        Event e = events.append("order", order.id(), new NewEvent(type, payload, actor, null, order.id(), Map.of()));
        metrics.count("oms.order.customer_contact", "type", type, "source", String.valueOf(payload.get("source")));
        out.put("recorded", true);
        out.put("eventId", e.id());
        return out;
    }

    private boolean alreadyRecorded(String orderId, String type, String externalId) {
        return events.load("order", orderId).stream()
            .anyMatch(e -> type.equals(e.type()) && e.payload() != null && Objects.equals(externalId, e.payload().get("externalId")));
    }
}
