package ai.intentoms.finance.payment;

import ai.intentoms.finance.model.Money;
import ai.intentoms.platform.ledger.Actor;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * PSP-agnostic payment lifecycle (FR-FIN-01): authorise per tender (split tender), re-authorise before expiry,
 * capture on shipment (partial capture across tenders), refund in original-tender proportion (FR-RET-04), void.
 *
 * <p>Deviation from ARCHITECTURE.md §3.5, documented here: because an order may carry several tenders, the three
 * operations that can touch more than one payment return lists instead of a single row —
 * {@link #reauthorizeIfExpiring} and {@link #capture} return {@code List<Payment>}, {@link #refund} returns
 * {@code List<Refund>} (one leg per original tender).</p>
 *
 * <p>Gateway declines are persisted and returned as rows in status {@code FAILED} with the matching
 * {@code payment.failed} / {@code refund.failed} ledger event rather than thrown, so callers (workflows, REST) can
 * decide how to react; preconditions such as insufficient authorised or captured funds raise
 * {@link ai.intentoms.platform.error.ConflictException} before any PSP call is made.</p>
 *
 * <p>Ledger events (aggregate {@code payment}, id = payment id): {@code payment.authorized}, {@code payment.reauthorized},
 * {@code payment.captured}, {@code payment.voided}, {@code payment.failed}. Aggregate {@code refund}, id = refund id:
 * {@code refund.requested}, {@code refund.succeeded}, {@code refund.failed}.</p>
 */
public interface PaymentService {
    /**
     * Places an authorisation hold for one tender. Call once per tender for split-tender orders.
     *
     * @param agentMandate delegated payment mandate for agent-placed orders (FR-FIN-06), or null / empty
     * @return the stored payment; status {@code AUTHORIZED}, or {@code FAILED} with {@code failureCode} when declined
     */
    Payment authorize(String orderId, Money amount, String tenderToken, String tenderType, Map<String, Object> agentMandate);

    /** Re-authorises every open payment on the order whose hold expires before {@code now + within}; returns the ones re-authorised. */
    List<Payment> reauthorizeIfExpiring(String orderId, Duration within);

    /**
     * Captures {@code amount} across the order's open payments in creation order (partial capture allowed).
     *
     * @return every payment touched by this capture (including ones that failed at the gateway)
     * @throws ai.intentoms.platform.error.ConflictException when uncaptured authorised funds are below {@code amount}
     */
    List<Payment> capture(String orderId, Money amount, String reason);

    /**
     * Refunds {@code amount} against the order's captured payments in proportion to what each tender captured
     * (original tender split, FR-RET-04). Runs the GR-05 {@link ai.intentoms.finance.guard.SpendGuard} first.
     *
     * @param returnId the RMA driving the refund, or null for a manual refund
     * @param actor    who requested the refund; recorded on the refund rows, events and the spend ledger
     * @return one refund leg per payment that received money
     * @throws ai.intentoms.platform.error.GuardrailViolationException when a velocity cap or the circuit breaker refuses the spend
     * @throws ai.intentoms.platform.error.ConflictException            when net captured funds are below {@code amount}
     */
    List<Refund> refund(String orderId, Money amount, String reason, String returnId, Actor actor);

    /** Releases every uncaptured authorisation on the order (cancellation); captured payments are left untouched. */
    void voidAuthorization(String orderId, String reason);

    List<Payment> forOrder(String orderId);

    List<Refund> refundsForOrder(String orderId);

    Payment get(String paymentId);
}
