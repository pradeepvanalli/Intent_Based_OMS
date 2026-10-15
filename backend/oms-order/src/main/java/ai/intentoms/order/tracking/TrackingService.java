package ai.intentoms.order.tracking;

import ai.intentoms.order.model.Address;
import ai.intentoms.order.model.FulfilmentGroup;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.model.OrderLine;
import ai.intentoms.order.model.OrderView;
import ai.intentoms.order.model.ShipmentSummary;
import ai.intentoms.order.service.OrderService;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.statemachine.model.EntityStatus;
import ai.intentoms.statemachine.model.Hold;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Builds the customer-safe {@link TrackingView} behind a signed tracking token. The tenant is taken from the token
 * (never from request headers) and bound for the duration of the read. The narrative is a deterministic template over
 * order facts, extended by {@link TrackingNarrativeProvider} when a model is available.
 */
@Service
public class TrackingService {
    private static final Logger log = LoggerFactory.getLogger(TrackingService.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMMM d", Locale.ENGLISH);
    private static final Set<String> INACTIVE_GROUPS = Set.of("rejected", "cancelled");

    private final OrderService orders;
    private final TrackingTokenService tokens;
    private final TrackingNarrativeProvider narrative;

    public TrackingService(OrderService orders, TrackingTokenService tokens, TrackingNarrativeProvider narrative) {
        this.orders = orders;
        this.tokens = tokens;
        this.narrative = narrative;
    }

    /** Issues a 30-day link for an order of the current tenant (404 when the order does not exist). */
    public TrackingTokenService.Issued issue(String orderId) {
        Order o = orders.get(orderId);
        return tokens.issue(TenantContext.require(), o.id(), Instant.now());
    }

    /** Resolves a public token to the customer-safe view. Invalid, tampered and expired tokens all read as not found. */
    public TrackingView track(String token) {
        TrackingTokenService.Claims claims = tokens.verify(token, Instant.now()).orElseThrow(TrackingService::invalidLink);
        return TenantContext.supplyAs(claims.tenantId(), () -> {
            OrderView view;
            try { view = orders.view(claims.orderId()); } catch (NotFoundException e) { throw invalidLink(); }
            return build(view, claims.expiresAt(), Instant.now());
        });
    }

    private static NotFoundException invalidLink() { return new NotFoundException("This tracking link is invalid or has expired", Map.of("code", "tracking_link_invalid")); }

    TrackingView build(OrderView view, Instant expiresAt, Instant now) {
        Order o = view.order();
        boolean pickup = isPickup(o);
        String status = o.status() == null ? "created" : o.status().toLowerCase(Locale.ROOT);
        boolean held = view.holds() != null && view.holds().stream().anyMatch(h -> h.resolvedAt() == null);
        String stage = stageOf(status, pickup);
        String label = statusLabel(status, pickup, held);
        TrackingView.Promise promise = promise(o);
        List<TrackingView.Item> items = items(view);
        List<TrackingView.Shipment> shipments = shipments(view);
        TrackingView.Pickup pickupLocation = pickup ? pickupLocation(o) : null;
        String text = template(o, stage, held, promise, shipments, pickupLocation, now);
        boolean generated = false;
        try {
            Optional<String> extra = narrative.narrative(view);
            if (extra.isPresent() && !extra.get().isBlank()) { text = text + "\n\n" + extra.get().trim(); generated = true; }
        } catch (RuntimeException e) {
            log.warn("tracking narrative provider failed for {}: {}", o.id(), e.getMessage());
        }
        return new TrackingView(o.channelOrderId(), status, label, stage, pickup, o.placedAt(), promise, items, shipments, pickupLocation, text, generated,
            nextStep(stage, held, pickup, pickupLocation), expiresAt, now);
    }

    static boolean isPickup(Order o) {
        return "pickup".equalsIgnoreCase(o.shipMethod()) || "bopis".equalsIgnoreCase(o.orderType()) || o.address("pickup").isPresent();
    }

    static String stageOf(String status, boolean pickup) {
        return switch (status) {
            case "cancelled", "canceled" -> "cancelled";
            case "delivered", "picked_up" -> "delivered";
            case "shipped", "partially_delivered", "ready_for_pickup", "partially_picked_up" -> "shipped";
            case "released", "partially_shipped", "partially_ready", "picking", "packed" -> "confirmed";
            default -> "placed";
        };
    }

    static String statusLabel(String status, boolean pickup, boolean held) {
        return switch (status) {
            case "cancelled", "canceled" -> "Cancelled";
            case "delivered" -> "Delivered";
            case "picked_up" -> "Picked up";
            case "partially_picked_up" -> "Partly picked up";
            case "ready_for_pickup" -> "Ready for pickup";
            case "partially_ready" -> "Partly ready for pickup";
            case "shipped" -> "On its way";
            case "partially_delivered" -> "Partly delivered";
            case "partially_shipped" -> "Partly shipped";
            case "released", "picking", "packed" -> pickup ? "Being prepared for pickup" : "Being packed";
            case "on_hold" -> "Being reviewed";
            default -> held ? "Being reviewed" : "Order received";
        };
    }

    static String lineLabel(String state) {
        if (state == null) return "Processing";
        return switch (state.toLowerCase(Locale.ROOT)) {
            case "shipped" -> "Shipped";
            case "delivered" -> "Delivered";
            case "cancelled", "canceled" -> "Cancelled";
            case "backordered" -> "Backordered";
            case "released", "picking", "packed", "picked" -> "Being prepared";
            case "staged_for_pickup", "ready_for_pickup" -> "Ready for pickup";
            case "picked_up" -> "Picked up";
            default -> "Processing";
        };
    }

    static String shipmentLabel(String status) {
        if (status == null) return "Label created";
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "delivered" -> "Delivered";
            case "out_for_delivery" -> "Out for delivery";
            case "in_transit", "shipped" -> "In transit";
            case "exception", "carrier_exception" -> "Delivery delayed";
            case "returned_to_sender" -> "Returned to sender";
            default -> "Label created";
        };
    }

    private static TrackingView.Promise promise(Order o) {
        List<FulfilmentGroup> groups = o.fulfilmentGroups() == null ? List.of() : o.fulfilmentGroups().stream()
            .filter(g -> g.promiseDate() != null && (g.status() == null || !INACTIVE_GROUPS.contains(g.status().toLowerCase(Locale.ROOT)))).toList();
        if (groups.isEmpty()) return null;
        LocalDate earliest = groups.stream().map(FulfilmentGroup::promiseDate).min(LocalDate::compareTo).orElse(null);
        LocalDate latest = groups.stream().map(FulfilmentGroup::promiseDate).max(LocalDate::compareTo).orElse(null);
        return new TrackingView.Promise(earliest, latest, o.shipMethod());
    }

    private static List<TrackingView.Item> items(OrderView view) {
        List<TrackingView.Item> out = new ArrayList<>();
        for (OrderLine l : view.order().lines()) {
            String state = view.lineStatuses() == null ? null : view.lineStatuses().stream()
                .filter(s -> s.entityId() != null && s.entityId().endsWith(":" + l.lineId())).map(EntityStatus::state).findFirst().orElse(l.status());
            out.add(new TrackingView.Item(name(l), l.qty(), lineLabel(state)));
        }
        return out;
    }

    private static String name(OrderLine l) { return l.description() == null || l.description().isBlank() ? l.sku() : l.description(); }

    private static List<TrackingView.Shipment> shipments(OrderView view) {
        if (view.shipments() == null) return List.of();
        List<TrackingView.Shipment> out = new ArrayList<>();
        for (ShipmentSummary s : view.shipments()) {
            List<String> names = s.lines() == null ? List.of() : s.lines().stream()
                .map(sl -> view.order().line(sl.lineId()).map(TrackingService::name).orElse(null)).filter(Objects::nonNull).toList();
            out.add(new TrackingView.Shipment(s.carrier(), s.service(), s.tracking(), trackingUrl(s.carrier(), s.tracking()), shipmentLabel(s.status()), s.shippedAt(), s.deliveredAt(), s.eta(), names));
        }
        return out;
    }

    /** Public carrier tracking pages for the carriers the demo and the mock connectors emit; null for anything else. */
    static String trackingUrl(String carrier, String tracking) {
        if (carrier == null || tracking == null || tracking.isBlank()) return null;
        String c = carrier.toLowerCase(Locale.ROOT);
        if (c.contains("ups")) return "https://www.ups.com/track?tracknum=" + tracking;
        if (c.contains("fedex")) return "https://www.fedex.com/fedextrack/?trknbr=" + tracking;
        if (c.contains("usps")) return "https://tools.usps.com/go/TrackConfirmAction?tLabels=" + tracking;
        if (c.contains("dhl")) return "https://www.dhl.com/en/express/tracking.html?AWB=" + tracking;
        return null;
    }

    private static TrackingView.Pickup pickupLocation(Order o) {
        Address a = o.address("pickup").or(o::shipTo).orElse(null);
        if (a == null) return null;
        String line1 = a.line1() == null ? "" : a.line1().trim();
        boolean looksLikeNodeId = line1.matches("[A-Z0-9][A-Z0-9-]*") || line1.toLowerCase(Locale.ROOT).startsWith("store pickup");
        String store = !line1.isEmpty() && !looksLikeNodeId ? line1 : (a.city() == null ? "Our store" : a.city() + " store");
        return new TrackingView.Pickup(store, a.city(), a.state());
    }

    static String template(Order o, String stage, boolean held, TrackingView.Promise promise, List<TrackingView.Shipment> shipments, TrackingView.Pickup pickup, Instant now) {
        String n = o.channelOrderId();
        String placed = o.placedAt() == null ? "" : " on " + DATE.format(o.placedAt().atZone(ZoneOffset.UTC).toLocalDate());
        String by = promise == null || promise.latest() == null ? "" : " by " + DATE.format(promise.latest());
        String where = pickup == null ? "" : " at " + pickup.storeName();
        return switch (stage) {
            case "cancelled" -> "Order " + n + " has been cancelled. If you did not ask for this, please contact us and we will sort it out.";
            case "delivered" -> pickup != null
                ? "Order " + n + " was picked up" + where + ". Thank you for shopping with us."
                : "Order " + n + " was delivered" + deliveredOn(shipments) + ". We hope you enjoy it.";
            case "shipped" -> pickup != null
                ? "Order " + n + " is ready for pickup" + where + ". Bring your order number and a photo ID."
                : shippedText(n, shipments, promise);
            case "confirmed" -> pickup != null
                ? "Order " + n + " is confirmed and being prepared" + where + ". We will let you know as soon as it is ready" + by + "."
                : "Order " + n + " is confirmed and being packed. We expect it to arrive" + by + ".";
            default -> held
                ? "We received order " + n + placed + ". A quick check is in progress before it ships; nothing is needed from you right now."
                : "We received order " + n + placed + " and are getting it ready" + (pickup != null ? " for pickup" + where : "") + "." + (by.isEmpty() ? "" : " We expect it to arrive" + by + ".");
        };
    }

    private static String shippedText(String n, List<TrackingView.Shipment> shipments, TrackingView.Promise promise) {
        StringBuilder sb = new StringBuilder("Order ").append(n).append(" is on its way");
        TrackingView.Shipment first = shipments.isEmpty() ? null : shipments.get(0);
        if (first != null && first.carrier() != null) sb.append(" with ").append(first.carrier());
        if (first != null && first.tracking() != null) sb.append(" (tracking ").append(first.tracking()).append(')');
        sb.append('.');
        if (shipments.size() > 1) sb.append(" It is travelling in ").append(shipments.size()).append(" packages.");
        if (first != null && first.eta() != null) sb.append(" Expected delivery ").append(DATE.format(first.eta().atZone(ZoneOffset.UTC).toLocalDate())).append('.');
        else if (promise != null && promise.latest() != null) sb.append(" Expected delivery by ").append(DATE.format(promise.latest())).append('.');
        return sb.toString();
    }

    private static String deliveredOn(List<TrackingView.Shipment> shipments) {
        return shipments.stream().map(TrackingView.Shipment::deliveredAt).filter(Objects::nonNull).max(Instant::compareTo)
            .map(d -> " on " + DATE.format(d.atZone(ZoneOffset.UTC).toLocalDate())).orElse("");
    }

    static String nextStep(String stage, boolean held, boolean pickup, TrackingView.Pickup location) {
        return switch (stage) {
            case "cancelled" -> "Any payment taken will be refunded to the original payment method within a few days.";
            case "delivered" -> "Something not right? Start a return or contact us from your account.";
            case "shipped" -> pickup ? "Head to " + (location == null ? "the store" : location.storeName()) + " with your order number and a photo ID." : "Follow the carrier link for live updates. Someone may need to be present to receive the package.";
            case "confirmed" -> pickup ? "We will email you the moment your order is ready for pickup." : "We will email you the tracking number as soon as it ships.";
            default -> held ? "No action is needed; we will update this page as soon as the check is complete." : "No action is needed. We will update this page when your order is confirmed.";
        };
    }

    /** Hold types are internal; only their presence is exposed as "being reviewed". */
    @SuppressWarnings("unused")
    private static boolean active(Hold h) { return h.resolvedAt() == null; }
}
