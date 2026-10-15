package ai.intentoms.order.model;

import ai.intentoms.finance.model.Money;

/** Header-level charge or discount: type shipping | handling | gift_wrap | discount | tax | duty | fee. Negative amounts are discounts. */
public record Charge(String type, String code, Money amount, String description) {}
