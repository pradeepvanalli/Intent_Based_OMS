package ai.intentoms.order.model;

import ai.intentoms.finance.model.Money;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Output of sourcing (section 10 "FulfilmentGroup / Release"): lines released together from one node. */
public record FulfilmentGroup(String id, String nodeId, String policy, List<Allocation> allocations, LocalDate promiseDate, Double onTimeProbability,
                              String shipMethod, Money estimatedCost, String status, String sourcingPlanId, String decisionRecordId, Map<String, Object> explain) {
    public record Allocation(String lineId, String sku, int qty) {}
}
