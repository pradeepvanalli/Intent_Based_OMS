package ai.intentoms.returns.service;

import ai.intentoms.order.service.ReturnSummaryProvider;
import ai.intentoms.returns.model.ReturnSummary;
import ai.intentoms.returns.store.ReturnRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Implements the oms-order {@link ReturnSummaryProvider} SPI so the CS order view (FR-CS-01, UI contract item 4
 * {@code OrderView.returns[]}) lists an order's RMAs; replaces the {@code noReturns()} default of OrderAutoConfiguration.
 */
@Component
public class ReturnSummaryProviderImpl implements ReturnSummaryProvider {
    private final ReturnRepository returns;

    public ReturnSummaryProviderImpl(ReturnRepository returns) { this.returns = returns; }

    @Override
    public List<ai.intentoms.order.model.ReturnSummary> returnsFor(String orderId) {
        return returns.summariesForOrder(orderId).stream().map(ReturnSummaryProviderImpl::toOrderSummary).toList();
    }

    static ai.intentoms.order.model.ReturnSummary toOrderSummary(ReturnSummary s) {
        return new ai.intentoms.order.model.ReturnSummary(s.rma(), s.orderId(), s.status(), s.createdAt(), s.channel(), s.lineCount(), s.refundAmount(), s.riskScore(), s.refundTiming());
    }
}
