package ai.intentoms.returns.model;

import java.math.BigDecimal;

/**
 * One leg of a refund (FR-RET-04): a finance refund row against one original tender, or a store credit
 * ({@code paymentId} = {@code store_credit}).
 */
public record RefundLeg(String refundId, String paymentId, BigDecimal amount, String status) {}
