package ai.intentoms.finance.payment;

import ai.intentoms.finance.model.Money;

/**
 * PSP-agnostic payment gateway SPI (FR-FIN-01). One implementation is active per deployment, selected by
 * {@code oms.finance.psp}: {@link MockPaymentGateway} (default, deterministic, no network) or
 * {@link StripePaymentGateway}. Implementations translate gateway outcomes into result records rather than
 * throwing for business declines, so the {@link PaymentService} can persist failures and emit
 * {@code payment.failed} / {@code refund.failed} events; only transport-level faults raise
 * {@link PaymentGatewayException}.
 *
 * <p>Idempotency keys handed to {@link #capture} and {@link #refund} are the OMS capture id and refund id so a
 * retried call after a crash can never double-charge or double-refund.</p>
 */
public interface PaymentGateway {
    /** Short stable gateway name persisted in {@code payment.psp} ("mock", "stripe"). */
    String name();

    /** Places an authorisation hold for {@code r.amount()} against the tokenised tender. */
    AuthResult authorize(AuthRequest r);

    /** Re-authorises an expiring hold; returns the new PSP reference and expiry. */
    AuthResult reauthorize(String pspRef, Money amount);

    /** Captures up to the authorised amount (partial capture allowed). */
    CaptureResult capture(String pspRef, Money amount, String idempotencyKey);

    /** Refunds part or all of a captured amount back to the original tender. */
    RefundResult refund(String pspRef, Money amount, String idempotencyKey, String reason);

    /** Releases an uncaptured authorisation hold. */
    void voidAuth(String pspRef);
}
