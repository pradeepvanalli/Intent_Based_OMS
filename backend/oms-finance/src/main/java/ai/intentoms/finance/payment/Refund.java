package ai.intentoms.finance.payment;

import ai.intentoms.finance.model.Money;

import java.time.Instant;

/**
 * One refund leg against a single payment (FR-RET-04). A refund request that spans split tenders produces one
 * {@code Refund} per original payment, each carrying its proportional share.
 *
 * @param id          OMS refund id ({@code ref_...}), also the PSP idempotency key
 * @param orderId     owning order
 * @param paymentId   payment the money goes back to
 * @param returnId    RMA that triggered the refund, or null for manual / appeasement refunds
 * @param amount      refunded amount
 * @param reason      free-text reason recorded for reconciliation
 * @param status      lifecycle status
 * @param pspRef      PSP refund reference
 * @param failureCode gateway failure code when status is FAILED
 * @param requestedBy actor reference ("user:alice", "agent:shopbot", "system:returns")
 */
public record Refund(
    String id,
    String orderId,
    String paymentId,
    String returnId,
    Money amount,
    String reason,
    RefundStatus status,
    String pspRef,
    String failureCode,
    String requestedBy,
    Instant createdAt,
    Instant updatedAt
) {}
