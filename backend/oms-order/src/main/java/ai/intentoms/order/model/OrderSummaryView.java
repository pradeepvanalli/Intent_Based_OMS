package ai.intentoms.order.model;

import ai.intentoms.finance.model.Money;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * {@link OrderSummary} plus the extras the orders list renders (UI contract item 4): {@code total}, {@code lineCount},
 * node ids of the fulfilment groups, whether an active hold exists, SLA risk and the earliest promise date.
 */
public record OrderSummaryView(
    String id, String channel, String channelOrderId, String orderType, String customerRef, String status,
    Money total, int lineCount, Instant placedAt, Instant updatedAt,
    String intentPolicy, boolean agentOrder, String agentId, String primaryNodeId, List<String> nodeIds,
    Double marginRatio, boolean held, boolean atRisk, Double riskScore, LocalDate promiseDate
) {
    public static OrderSummaryView of(OrderSummary s, String agentId, List<String> nodeIds, boolean held, OrderRisk risk, LocalDate promiseDate) {
        Money total = s.grandTotal() == null ? null : new Money(s.grandTotal(), s.currency() == null ? "USD" : s.currency());
        return new OrderSummaryView(s.id(), s.channel(), s.channelOrderId(), s.orderType(), s.customerRef(), s.status(), total, s.lineCount(), s.placedAt(), s.updatedAt(),
            s.intentPolicy(), s.agentOrder(), agentId, s.primaryNodeId(), nodeIds == null ? List.of() : nodeIds, s.marginRatio(), held,
            risk != null && risk.atRisk(), risk == null ? null : risk.score(), promiseDate);
    }
}
