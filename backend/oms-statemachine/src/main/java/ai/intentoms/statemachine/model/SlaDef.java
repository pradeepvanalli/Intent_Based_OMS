package ai.intentoms.statemachine.model;

import java.time.Duration;

/**
 * SLA attached to a state (FR-SM-08): when any quantity has sat in the state for longer than {@code maxAge} the
 * engine appends an {@code sla.breached} event and publishes {@code SlaBreachedEvent} with {@code onBreach}
 * ({@code playbook.<name>} or {@code decision.<name>}) so monitoring can react.
 */
public record SlaDef(Duration maxAge, String onBreach) {}
