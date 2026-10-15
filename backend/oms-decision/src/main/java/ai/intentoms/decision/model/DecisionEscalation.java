package ai.intentoms.decision.model;

import java.time.Instant;

/** An L3 hand-off (table decision_escalation). {@code taskId} is set when a human-task implementation handles it. */
public record DecisionEscalation(String id, String recordId, String decision, String status, String taskId, Object resolvedValue, String resolvedBy, Instant resolvedAt, Instant createdAt) {}
