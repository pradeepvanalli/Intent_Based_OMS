package ai.intentoms.returns.service;

import ai.intentoms.returns.model.ReturnSummary;
import ai.intentoms.returns.store.ReturnRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Read-side lookup other modules (the order view, FR-CS-01; analytics) use to list the returns of an order without
 * depending on the full {@link ReturnService}. Candidate implementation of an {@code ai.intentoms.order.service.ReturnSummaryProvider}
 * SPI once oms-order publishes one.
 */
@Component
public class ReturnSummaryLookup {
    private final ReturnRepository returns;

    public ReturnSummaryLookup(ReturnRepository returns) { this.returns = returns; }

    public List<ReturnSummary> forOrder(String orderId) { return returns.summariesForOrder(orderId); }
}
