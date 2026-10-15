package ai.intentoms.returns.model;

import ai.intentoms.finance.model.Money;

import java.time.Instant;

/** Store credit issued in place of a tender refund (FR-RET-02 store_credit_only, FR-RET-04 gift cards). */
public record StoreCredit(String id, String customerRef, Money amount, String rmaId, String orderId, String reason, String issuedBy, Instant createdAt) {}
