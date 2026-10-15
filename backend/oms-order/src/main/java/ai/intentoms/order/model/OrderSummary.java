package ai.intentoms.order.model;

import java.math.BigDecimal;
import java.time.Instant;

/** Lightweight row for lists and the control tower. */
public record OrderSummary(String id, String channel, String channelOrderId, String orderType, String customerRef, String status, BigDecimal grandTotal, String currency,
                           String intentPolicy, boolean agentOrder, String primaryNodeId, Double marginRatio, Instant placedAt, Instant updatedAt, int lineCount) {}
