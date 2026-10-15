package ai.intentoms.statemachine.model;

/**
 * Roll-up rule deriving a header state from child quantities (FR-SM-05). Rules are evaluated in ascending
 * {@code priority} order (ties keep declaration order); the first whose CEL {@code expression} is true wins.
 * CEL roots: {@code state} (map stateId → qty, zero-filled for the header model's own state ids), {@code lines}
 * (list of {state, qty}), {@code ctx} ({@code total}, {@code states_with_qty}, {@code distinct}, {@code max_state},
 * {@code single_state}).
 */
public record RollupRule(String parentState, String expression, int priority) {}
