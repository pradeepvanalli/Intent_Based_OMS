package ai.intentoms.statemachine.model;

import java.time.Instant;

/** Pending or fired SLA timer row (FR-SM-08). */
public record SlaTimer(String id, String tenantId, String entityType, String entityId, String state, Instant enteredAt, Instant dueAt,
                       String onBreach, Instant firedAt, Instant cancelledAt) {}
