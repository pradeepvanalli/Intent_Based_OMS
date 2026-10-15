package ai.intentoms.returns.service;

import ai.intentoms.finance.model.Money;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.returns.model.StoreCredit;

/**
 * SPI for issuing store credit / gift cards instead of a tender refund (FR-RET-02 {@code store_credit_only},
 * FR-RET-04 gift cards). The default implementation writes table {@code store_credit}; a tenant integration can replace
 * it with a loyalty or gift-card platform by declaring its own bean.
 */
public interface StoreCreditIssuer {
    StoreCredit issue(String customerRef, Money amount, String rmaId, String orderId, String reason, Actor actor);
}
