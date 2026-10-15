package ai.intentoms.returns.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Refund state on an RMA (FR-RET-04).
 *
 * @param amount          net amount to refund (merchandise + tax share + shipping share − restocking fee)
 * @param currency        order currency
 * @param restockingFee   fee withheld, zero when none applies
 * @param taxRefunded     tax share included in {@code amount}
 * @param shippingRefunded shipping share included in {@code amount}
 * @param method          original_tender | store_credit
 * @param legs            legs issued so far
 * @param status          pending | awaiting_approval | succeeded | failed
 * @param approvalTaskId  the refund_approvals task when status is awaiting_approval
 * @param holdReason      above_threshold | guardrail when awaiting approval
 * @param approvedBy      actor ref of the approver, once approved
 * @param issuedAt        when the money / credit moved
 */
public record RefundInfo(BigDecimal amount, String currency, BigDecimal restockingFee, BigDecimal taxRefunded, BigDecimal shippingRefunded, String method,
                         List<RefundLeg> legs, String status, String approvalTaskId, String holdReason, String approvedBy, Instant issuedAt) {
    public static final String PENDING = "pending";
    public static final String AWAITING_APPROVAL = "awaiting_approval";
    public static final String SUCCEEDED = "succeeded";
    public static final String FAILED = "failed";
    public static final String ORIGINAL_TENDER = "original_tender";
    public static final String STORE_CREDIT = "store_credit";

    public RefundInfo {
        legs = legs == null ? List.of() : List.copyOf(legs);
    }

    public boolean succeeded() { return SUCCEEDED.equals(status); }
    public boolean awaitingApproval() { return AWAITING_APPROVAL.equals(status); }

    public RefundInfo withStatus(String s) { return new RefundInfo(amount, currency, restockingFee, taxRefunded, shippingRefunded, method, legs, s, approvalTaskId, holdReason, approvedBy, issuedAt); }
    public RefundInfo awaiting(String taskId, String reason) { return new RefundInfo(amount, currency, restockingFee, taxRefunded, shippingRefunded, method, legs, AWAITING_APPROVAL, taskId, reason, approvedBy, issuedAt); }
    public RefundInfo approved(String by) { return new RefundInfo(amount, currency, restockingFee, taxRefunded, shippingRefunded, method, legs, PENDING, approvalTaskId, holdReason, by, issuedAt); }
    public RefundInfo issued(List<RefundLeg> newLegs, String s, Instant at) { return new RefundInfo(amount, currency, restockingFee, taxRefunded, shippingRefunded, method, newLegs, s, approvalTaskId, holdReason, approvedBy, at); }
}
