package ai.intentoms.finance.payment;

import ai.intentoms.finance.model.Money;

import java.time.Instant;
import java.util.Map;

/**
 * One tender authorisation on an order (FR-FIN-01). Split-tender orders have several payments; capture walks them
 * in creation order and refunds are split across them in proportion to what each captured (FR-RET-04).
 *
 * @param id               OMS payment id ({@code pay_...})
 * @param orderId          owning order
 * @param tenderType       {@link TenderType} code
 * @param tenderToken      tokenised tender reference (never a PAN)
 * @param psp              gateway name
 * @param pspRef           current PSP authorisation reference
 * @param status           lifecycle status
 * @param amountAuthorized total currently authorised
 * @param amountCaptured   total captured so far
 * @param authExpiresAt    when the authorisation lapses (null for tenders that do not expire)
 * @param agentMandate     delegated payment mandate kept as dispute evidence (FR-FIN-06), or empty
 * @param failureCode      last gateway failure code, when status is FAILED
 */
public record Payment(
    String id,
    String orderId,
    String tenderType,
    String tenderToken,
    String psp,
    String pspRef,
    PaymentStatus status,
    Money amountAuthorized,
    Money amountCaptured,
    Instant authExpiresAt,
    Map<String, Object> agentMandate,
    String failureCode,
    Instant createdAt,
    Instant updatedAt
) {
    public String currency() { return amountAuthorized.currency(); }

    /** Authorised funds not yet captured. */
    public Money uncaptured() { return amountAuthorized.subtract(amountCaptured); }
}
