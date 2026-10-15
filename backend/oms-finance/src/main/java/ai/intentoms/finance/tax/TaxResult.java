package ai.intentoms.finance.tax;

import ai.intentoms.finance.model.Money;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Tax quote (FR-FIN-02).
 *
 * @param total        total tax across lines and shipping
 * @param perLine      tax per line id; shipping tax, when applicable, appears under key {@link #SHIPPING_KEY}
 * @param perLineRates effective rate per line id (fraction, e.g. 0.0725)
 * @param engine       engine that produced the quote
 */
public record TaxResult(Money total, Map<String, Money> perLine, Map<String, BigDecimal> perLineRates, String engine) {
    public static final String SHIPPING_KEY = "shipping";
}
