package ai.intentoms.statemachine.model;

import java.util.List;

/**
 * Outcome of {@code StateMachine.fire}. When several source states were drained by one request (quantity-level model,
 * {@code qty} null), {@code fromState} (and {@code toState} if targets differ) are comma-joined, {@code qty} is the total
 * moved and {@code eventId} is the first ledger event written. {@code blockedBy} lists the guard texts, hold ids or
 * reasons that prevented a match.
 */
public record TransitionResult(boolean applied, String fromState, String toState, int qty, List<String> actions, String eventId, List<String> blockedBy) {
    public TransitionResult {
        actions = actions == null ? List.of() : List.copyOf(actions);
        blockedBy = blockedBy == null ? List.of() : List.copyOf(blockedBy);
    }

    public static TransitionResult blocked(List<String> reasons) { return new TransitionResult(false, null, null, 0, List.of(), null, reasons); }
}
