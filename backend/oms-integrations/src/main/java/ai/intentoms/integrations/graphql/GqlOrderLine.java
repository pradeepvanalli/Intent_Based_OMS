package ai.intentoms.integrations.graphql;

import ai.intentoms.finance.model.Money;
import ai.intentoms.order.model.OrderLine;
import ai.intentoms.statemachine.model.QuantityBucket;

import java.util.List;
import java.util.Map;

/**
 * GraphQL view of an {@link OrderLine} that also carries its order id and, when the client selected it, the line's
 * quantity-by-state buckets from the state machine (FR-SM-02). Resolved once per order in
 * {@link OmsGraphQlController#lines}, so a 20-line order costs one status lookup, not twenty.
 */
public record GqlOrderLine(String orderId, String lineId, String sku, String description, int qty, Money unitPrice, Money discount, Money tax,
                           String fulfilmentGroupId, String status, Map<String, Object> attributes, List<QuantityBucket> quantities) {
    static GqlOrderLine of(String orderId, OrderLine l, List<QuantityBucket> quantities) {
        return new GqlOrderLine(orderId, l.lineId(), l.sku(), l.description(), l.qty(), l.unitPrice(), l.discount(), l.tax(), l.fulfilmentGroupId(), l.status(),
            l.attributes(), quantities == null ? List.of() : quantities);
    }
}
