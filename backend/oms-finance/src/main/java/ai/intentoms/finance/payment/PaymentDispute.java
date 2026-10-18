package ai.intentoms.finance.payment;

import ai.intentoms.finance.model.Money;

import java.time.Instant;

/**
 * A chargeback against a payment (FD-06), as reported by the gateway. {@code status} is OPEN until the gateway
 * closes it as WON (merchant kept the money) or LOST; {@code fraud} is true when the reason says the cardholder did
 * not authorise the payment, which is what the fraud learners treat as confirmed fraud. Other reasons (item not
 * received, not as described) are still a cost, recorded as a payment outcome, but not a fraud label.
 */
public record PaymentDispute(String id, String paymentId, String orderId, String psp, String pspDisputeId, Money amount, String reason,
                             String status, boolean fraud, String agentId, Instant createdAt) {
    public static final String OPEN = "OPEN";
    public static final String WON = "WON";
    public static final String LOST = "LOST";
}
