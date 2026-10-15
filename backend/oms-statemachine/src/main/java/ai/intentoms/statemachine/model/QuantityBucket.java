package ai.intentoms.statemachine.model;

import java.time.Instant;

/** Quantity of an entity sitting in one state since {@code since} (FR-SM-02). */
public record QuantityBucket(String state, int qty, Instant since) {}
