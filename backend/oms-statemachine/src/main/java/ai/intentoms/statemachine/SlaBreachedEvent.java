package ai.intentoms.statemachine;

/**
 * Spring application event published by the SLA sweeper when quantity has sat in {@code state} longer than its
 * {@code maxAge} (FR-SM-08). {@code onBreach} is {@code playbook.<name>} or {@code decision.<name>}; the matching
 * {@code sla.breached} ledger event id is {@code eventId}.
 */
public record SlaBreachedEvent(String tenant, String entityType, String entityId, String state, String onBreach, long ageSeconds, String eventId) {}
