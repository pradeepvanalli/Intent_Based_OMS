package ai.intentoms.order.service;

import ai.intentoms.order.model.OrderRisk;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * SPI through which the monitoring module supplies SLA breach risk (decision {@code sla_breach_risk}, FR-MON-03) for the
 * order view and list. Default: no risk known.
 */
public interface OrderRiskProvider {
    Optional<OrderRisk> riskFor(String orderId);

    /** Batch form for lists; the default fans out to {@link #riskFor}. */
    default Map<String, OrderRisk> riskFor(Collection<String> orderIds) {
        Map<String, OrderRisk> out = new java.util.LinkedHashMap<>();
        for (String id : orderIds) riskFor(id).ifPresent(r -> out.put(id, r));
        return out;
    }

    static OrderRiskProvider none() {
        return new OrderRiskProvider() {
            @Override public Optional<OrderRisk> riskFor(String orderId) { return Optional.empty(); }
            @Override public Map<String, OrderRisk> riskFor(Collection<String> orderIds) { return Map.of(); }
        };
    }
}
