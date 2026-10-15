package ai.intentoms.finance.tax;

/**
 * Tax calculation SPI (FR-FIN-02): called at capture and on every order change. One implementation is active,
 * selected by {@code oms.finance.tax}: {@link FlatRateTaxEngine} (default, tenant-configured per-state rates) or
 * {@link AvalaraTaxEngine} (AvaTax CreateTransaction).
 */
public interface TaxEngine {
    /** Stable engine name recorded on every {@link TaxResult} ("flat", "avalara"). */
    String name();

    TaxResult calculate(TaxRequest r);
}
