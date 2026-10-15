package ai.intentoms.statemachine.service;

/**
 * Maps entity types to ledger aggregates (ARCHITECTURE §2): {@code order}/{@code order_line} → {@code order} (line ids are
 * {@code "<orderId>:<lineId>"}, the order id is the part before the colon), {@code return}/{@code return_line} → {@code return},
 * {@code shipment}, {@code payment}, {@code refund} → themselves; any other entity type is its own aggregate.
 */
public final class Aggregates {
    private Aggregates() {}

    public record AggregateRef(String type, String id) {}

    public static AggregateRef of(String entityType, String entityId) {
        if (entityType == null) throw new IllegalArgumentException("entityType is required");
        return switch (entityType) {
            case "order", "order_line" -> new AggregateRef("order", rootId(entityType, entityId));
            case "return", "return_line" -> new AggregateRef("return", rootId(entityType, entityId));
            case "shipment", "payment", "refund" -> new AggregateRef(entityType, entityId);
            default -> new AggregateRef(entityType.endsWith("_line") ? entityType.substring(0, entityType.length() - 5) : entityType, rootId(entityType, entityId));
        };
    }

    private static String rootId(String entityType, String entityId) {
        if (entityType.endsWith("_line") && entityId != null) {
            int i = entityId.indexOf(':');
            if (i > 0) return entityId.substring(0, i);
        }
        return entityId;
    }
}
