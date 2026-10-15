package ai.intentoms.order.model;

import java.util.List;

/** SLA breach risk for an open order (FR-MON-03): score 0–4, level low|medium|high|critical, reasons and the scoring record. */
public record OrderRisk(double score, String level, List<String> reasons, String decisionRecordId) {
    public OrderRisk { reasons = reasons == null ? List.of() : List.copyOf(reasons); }
    public boolean atRisk() { return score >= 3; }
}
