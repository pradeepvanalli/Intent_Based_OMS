package ai.intentoms.order.model;

import ai.intentoms.finance.model.Money;

public record Totals(Money merchandise, Money discounts, Money shipping, Money tax, Money duties, Money fees, Money grandTotal) {}
