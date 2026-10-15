package ai.intentoms.order.model;

import java.time.Instant;
import java.util.List;

public record OrderQuery(List<String> statuses, String channel, String orderType, String customerRef, String nodeId, Instant placedFrom, Instant placedTo,
                         String text, Boolean agentOrders, Boolean belowFloor, int offset, int limit) {
    public OrderQuery {
        if (limit <= 0 || limit > 500) limit = 50;
        if (offset < 0) offset = 0;
    }
    public static OrderQuery all(int offset, int limit) { return new OrderQuery(null, null, null, null, null, null, null, null, null, null, offset, limit); }
}
