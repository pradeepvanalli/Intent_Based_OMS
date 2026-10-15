package ai.intentoms.order.service;

import ai.intentoms.order.model.ReturnSummary;

import java.util.List;

/** SPI through which the returns module supplies RMAs for the order view (FR-CS-01). Default: none. */
public interface ReturnSummaryProvider {
    List<ReturnSummary> returnsFor(String orderId);

    static ReturnSummaryProvider none() { return orderId -> List.of(); }
}
