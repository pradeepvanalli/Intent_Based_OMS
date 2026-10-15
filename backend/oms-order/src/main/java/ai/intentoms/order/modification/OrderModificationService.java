package ai.intentoms.order.modification;

import ai.intentoms.order.model.Order;
import ai.intentoms.platform.ledger.Actor;

/**
 * Customer-service changes to a captured order (FR-CS-02, UC-07): feasibility against the current line quantity
 * statuses with the cost of the change (including carrier intercept where the tenant allows it), then application
 * through state-machine transitions, re-pricing and a workflow signal.
 */
public interface OrderModificationService {
    /** Checks the change without side effects. */
    Feasibility check(String orderId, Modification m);

    /**
     * Applies the change: fires the state transitions ({@code api.cancel_line}, {@code api.cancel_order}), updates the
     * canonical order (lines, addresses, ship method), re-computes tax, totals and margin, appends {@code order.modified}
     * (plus {@code line.cancelled} / {@code order.cancelled}) and signals the order workflow {@code order.modified}.
     * Permissions: {@code order.cancel} for cancellations, {@code order.modify} otherwise.
     *
     * @throws ai.intentoms.platform.error.ConflictException when {@link #check} says the change is not allowed
     */
    Order apply(String orderId, Modification m, String reason, Actor actor);
}
