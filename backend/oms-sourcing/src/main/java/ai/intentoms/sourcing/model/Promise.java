package ai.intentoms.sourcing.model;

import ai.intentoms.finance.model.Money;

import java.time.LocalDate;
import java.util.List;

/**
 * Delivery promise (FR-SRC-05) with trade-offs (FR-SRC-06).
 *
 * @param fromNodeId   node expected to fulfil (null when nothing is in stock anywhere and the date is a backorder estimate)
 * @param alternatives other ship methods with their date, on-time probability and cost delta vs the requested method
 */
public record Promise(LocalDate date, double onTimeProbability, String fromNodeId, String shipMethod, boolean fromCache, long latencyMs, List<PromiseOption> alternatives) {
    public record PromiseOption(String shipMethod, LocalDate date, double onTimeProbability, String fromNodeId, Money costDelta) {}

    public Promise {
        alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
    }

    public Promise asCached(long latency) { return new Promise(date, onTimeProbability, fromNodeId, shipMethod, true, latency, alternatives); }
    public Promise withLatency(long latency) { return new Promise(date, onTimeProbability, fromNodeId, shipMethod, fromCache, latency, alternatives); }
}
