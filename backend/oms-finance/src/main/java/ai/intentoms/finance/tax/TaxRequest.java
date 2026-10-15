package ai.intentoms.finance.tax;

import ai.intentoms.finance.model.Money;

import java.util.List;

/**
 * Tax quote request (FR-FIN-02).
 *
 * @param lines        taxable lines
 * @param shipping     shipping charge (taxed only where the tenant marks shipping taxable), may be null
 * @param destination  ship-to jurisdiction
 * @param customerCode customer reference for external engines (exemption certificates); may be null
 */
public record TaxRequest(List<TaxLine> lines, Money shipping, TaxDestination destination, String customerCode) {
    public TaxRequest(List<TaxLine> lines, Money shipping, TaxDestination destination) { this(lines, shipping, destination, null); }

    /** Currency of the request, taken from the first line or the shipping charge. */
    public String currency() {
        if (lines != null) for (TaxLine l : lines) if (l.amount() != null) return l.amount().currency();
        if (shipping != null) return shipping.currency();
        return "USD";
    }
}
