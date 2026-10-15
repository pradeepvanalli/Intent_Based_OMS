package ai.intentoms.decision.runtime;

import ai.intentoms.decision.record.DecisionEscalationRepository;
import ai.intentoms.platform.ids.Ids;

/**
 * Default L3 handler: records the escalation so a human queue can pick it up; no task system required. Registered by
 * {@code DecisionAutoConfiguration} only when no other module provides an {@link EscalationHandler} bean.
 */
public class RecordingEscalationHandler implements EscalationHandler {
    private final DecisionEscalationRepository escalations;

    public RecordingEscalationHandler(DecisionEscalationRepository escalations) { this.escalations = escalations; }

    @Override
    public String escalate(EscalationRequest request) {
        String id = Ids.prefixed("esc");
        escalations.insert(id, request.recordId(), request.decision(), null);
        return id;
    }
}
