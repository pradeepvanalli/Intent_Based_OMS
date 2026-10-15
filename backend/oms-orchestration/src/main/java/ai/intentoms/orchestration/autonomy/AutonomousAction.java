package ai.intentoms.orchestration.autonomy;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Audit record of one autonomous (or attempted-autonomous) remediation (Agentic Control Tower). Written exactly once
 * per execution attempt, whether it succeeded or not, so every autonomous action is traceable end to end: the order,
 * the decision record that diagnosed it, the guardrail configuration in force at the time, and — if undone — who
 * undid it and why.
 *
 * @param outcome {@code executed} (the mapped service call completed) or {@code failed} (attempted but the underlying
 *                service found nothing to do, or the call failed); a failed attempt always raises a human task.
 */
public record AutonomousAction(
    String id,
    String tenantId,
    String orderId,
    String anomalyType,
    String decisionRecordId,
    String diagnosisValue,
    double confidence,
    String actionTaken,
    Map<String, Object> guardrailSnapshot,
    String outcome,
    boolean undoable,
    String detail,
    Map<String, Object> details,
    Instant executedAt,
    Instant undoneAt,
    String undoneBy,
    String undoReason
) {
    public static final String OUTCOME_EXECUTED = "executed";
    public static final String OUTCOME_FAILED = "failed";

    public AutonomousAction {
        // Map.copyOf rejects null values, and the guardrail snapshot legitimately carries one (an unset
        // minConfidenceOverride), so this uses the same null-tolerant unmodifiable-copy pattern as Answer/DecisionResult.
        guardrailSnapshot = guardrailSnapshot == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(guardrailSnapshot));
        details = details == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public boolean isUndone() { return undoneAt != null; }
}
