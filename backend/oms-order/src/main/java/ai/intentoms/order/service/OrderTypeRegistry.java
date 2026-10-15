package ai.intentoms.order.service;

import ai.intentoms.order.model.OrderType;

import java.util.List;

/**
 * Registry of the tenant's configurable order types (FR-CAP-03). Types are data: seeded from
 * {@code config/tenants/<t>/order-types.yaml} by {@link OrderTypeLoader}, edited at runtime via {@link #upsert}.
 */
public interface OrderTypeRegistry {
    /** @throws ai.intentoms.platform.error.NotFoundException when the type is not configured for the tenant */
    OrderType get(String name);

    List<OrderType> all();

    OrderType upsert(OrderType type);
}
