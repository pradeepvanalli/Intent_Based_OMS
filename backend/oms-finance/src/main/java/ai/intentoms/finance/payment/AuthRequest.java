package ai.intentoms.finance.payment;

import ai.intentoms.finance.model.Money;

import java.util.Map;

/**
 * Authorisation request handed to a {@link PaymentGateway} (FR-FIN-01, FR-FIN-06).
 *
 * @param paymentId    OMS payment id, also used as the PSP idempotency key
 * @param orderId      order the hold belongs to (sent as PSP metadata)
 * @param amount       amount to hold
 * @param tenderToken  tokenised tender reference (PSP payment-method token / gift card token); never a PAN
 * @param tenderType   one of {@link TenderType}
 * @param agentMandate delegated payment mandate for agent-placed orders, or empty
 */
public record AuthRequest(String paymentId, String orderId, Money amount, String tenderToken, String tenderType, Map<String, Object> agentMandate) {}
