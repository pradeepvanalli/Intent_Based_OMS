package ai.intentoms.orchestration.autonomy;

import java.time.Instant;
import java.util.List;

/**
 * One anomaly the scanner found and diagnosed (Agentic Control Tower live feed): the candidate signal that flagged
 * the order, the {@code stuck_order_diagnosis} decision record and value, and how it was resolved — either an
 * {@link AutonomousAction} (outcome {@code executed}) or a human task (outcome {@code escalated}, with the queue and
 * the reason a person is needed: the decision itself escalated, or a guardrail blocked autonomous execution).
 */
public record Anomaly(
    String id,
    String tenantId,
    String orderId,
    String anomalyType,
    String decisionRecordId,
    String diagnosisValue,
    Double confidence,
    String band,
    List<String> reasonCodes,
    String outcome,
    String actionId,
    String escalationTaskId,
    String escalationQueue,
    String escalationReason,
    Instant detectedAt
) {
    public static final String OUTCOME_EXECUTED = "executed";
    public static final String OUTCOME_ESCALATED = "escalated";

    public Anomaly { reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes); }
}
