package ai.intentoms.monitoring.sla;

import java.time.Instant;
import java.util.List;

/** Stored breach-risk score of one open order (FR-MON-02, table {@code order_risk}); {@code level} is {@code low|medium|high|critical}. */
public record OrderRisk(String orderId, int score, String level, List<String> reasons, String decisionRecordId, Instant scoredAt) {
    public OrderRisk { reasons = reasons == null ? List.of() : List.copyOf(reasons); }
}
