package ai.intentoms.decision.runtime;

/**
 * SPI for the L3 human layer (FR-DEC-02, GR-07). The decision module ships {@code RecordingEscalationHandler}, which
 * stores a decision_escalation row; orchestration overrides it with a human-task implementation by declaring its own
 * bean (the default is {@code @ConditionalOnMissingBean}). Returns the escalation id stored on the record.
 */
public interface EscalationHandler {
    String escalate(EscalationRequest request);
}
