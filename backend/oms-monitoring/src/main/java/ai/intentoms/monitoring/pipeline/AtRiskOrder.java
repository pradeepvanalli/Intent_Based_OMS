package ai.intentoms.monitoring.pipeline;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** At-risk list row (FR-MON-02, UI contract item 8): the stored breach-risk score joined with the order header. */
public record AtRiskOrder(String orderId, String channelOrderId, String status, String orderType, String channel, String nodeId, int riskScore, String riskLevel,
                          List<String> reasons, long ageMinutes, LocalDate promiseDate, String decisionRecordId, Instant updatedAt) {}
