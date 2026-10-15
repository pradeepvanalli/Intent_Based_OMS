package ai.intentoms.monitoring.sla;

import ai.intentoms.order.service.OrderRiskProvider;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Plugs the breach-risk store into the order module's {@link OrderRiskProvider} SPI (FR-MON-02, UI contract item 4
 * {@code OrderView.risk} and {@code OrderSummary.riskScore/atRisk}). Registering this bean replaces the order module's
 * empty default ({@code OrderAutoConfiguration}, {@code @ConditionalOnMissingBean}).
 */
@Component
public class MonitoringOrderRiskProvider implements OrderRiskProvider {
    private final OrderRiskRepository risks;

    public MonitoringOrderRiskProvider(OrderRiskRepository risks) { this.risks = risks; }

    @Override
    public Optional<ai.intentoms.order.model.OrderRisk> riskFor(String orderId) { return risks.find(orderId).map(MonitoringOrderRiskProvider::toOrderModel); }

    @Override
    public Map<String, ai.intentoms.order.model.OrderRisk> riskFor(Collection<String> orderIds) {
        Map<String, ai.intentoms.order.model.OrderRisk> out = new LinkedHashMap<>();
        risks.findAll(orderIds).forEach((id, r) -> out.put(id, toOrderModel(r)));
        return out;
    }

    static ai.intentoms.order.model.OrderRisk toOrderModel(OrderRisk r) {
        return new ai.intentoms.order.model.OrderRisk(r.score(), r.level(), r.reasons(), r.decisionRecordId());
    }
}
