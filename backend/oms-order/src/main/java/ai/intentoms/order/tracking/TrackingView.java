package ai.intentoms.order.tracking;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Customer-facing view of an order for the public tracking page ({@code GET /public/track/{token}}). Deliberately
 * narrow (GR-15): order number, plain-words status and stage, items by name and quantity, carrier tracking, pickup
 * store, promise window, a plain-language narrative and the next step. Never costs, margins, node ids, holds or
 * decision internals.
 *
 * @param stage one of placed | confirmed | shipped | delivered | cancelled (drives the stepper)
 */
public record TrackingView(
    String orderNumber,
    String status,
    String statusLabel,
    String stage,
    boolean pickup,
    Instant placedAt,
    Promise promise,
    List<Item> items,
    List<Shipment> shipments,
    Pickup pickupLocation,
    String narrative,
    boolean narrativeGenerated,
    String nextStep,
    Instant expiresAt,
    Instant generatedAt
) {
    public record Promise(LocalDate earliest, LocalDate latest, String shipMethod) {}
    public record Item(String name, int qty, String status) {}
    public record Shipment(String carrier, String service, String tracking, String trackingUrl, String status, Instant shippedAt, Instant deliveredAt, Instant eta, List<String> items) {}
    public record Pickup(String storeName, String city, String state) {}
}
