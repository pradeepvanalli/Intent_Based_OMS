package ai.intentoms.finance.tax;

import ai.intentoms.finance.config.FinanceConfigKeys;
import ai.intentoms.finance.model.Money;
import ai.intentoms.platform.config.TenantConfigStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Default {@link TaxEngine} (FR-FIN-02) using tenant-configured flat rates: {@code tax.rates} maps destination
 * state → rate and {@code tax.default_rate} (default 0) applies elsewhere; {@code tax.shipping_taxable} (default
 * false) controls whether shipping is taxed at the same rate. Lines with tax code {@code EXEMPT} or {@code NT} are
 * untaxed. Deterministic and network-free, so it is also the engine the test-suite runs against.
 */
@Component
@ConditionalOnProperty(name = "oms.finance.tax", havingValue = "flat", matchIfMissing = true)
public class FlatRateTaxEngine implements TaxEngine {
    public static final String NAME = "flat";
    private static final Set<String> EXEMPT_CODES = Set.of("EXEMPT", "NT", "NON_TAXABLE");

    private final TenantConfigStore config;

    public FlatRateTaxEngine(TenantConfigStore config) { this.config = config; }

    @Override
    public String name() { return NAME; }

    @Override
    public TaxResult calculate(TaxRequest r) {
        String ccy = r.currency();
        BigDecimal rate = rateFor(r.destination());
        Map<String, Money> perLine = new LinkedHashMap<>();
        Map<String, BigDecimal> perRate = new LinkedHashMap<>();
        Money total = Money.zero(ccy);
        if (r.lines() != null) {
            for (TaxLine l : r.lines()) {
                boolean exempt = l.taxCode() != null && EXEMPT_CODES.contains(l.taxCode().trim().toUpperCase(Locale.ROOT));
                BigDecimal lineRate = exempt ? BigDecimal.ZERO : rate;
                Money tax = l.amount() == null || l.amount().isNegative() ? Money.zero(ccy) : l.amount().multiply(lineRate);
                perLine.put(l.lineId(), tax);
                perRate.put(l.lineId(), lineRate);
                total = total.add(tax);
            }
        }
        if (r.shipping() != null && r.shipping().isPositive() && config.getBoolean(FinanceConfigKeys.TAX_SHIPPING_TAXABLE, false)) {
            Money tax = r.shipping().multiply(rate);
            perLine.put(TaxResult.SHIPPING_KEY, tax);
            perRate.put(TaxResult.SHIPPING_KEY, rate);
            total = total.add(tax);
        }
        return new TaxResult(total, perLine, perRate, NAME);
    }

    /** Rate for a destination: {@code tax.rates[STATE]}, else {@code tax.default_rate}, else 0. */
    public BigDecimal rateFor(TaxDestination d) {
        BigDecimal dflt = config.getDecimal(FinanceConfigKeys.TAX_DEFAULT_RATE, BigDecimal.ZERO);
        if (d == null || d.state() == null) return dflt;
        Object v = config.getMap(FinanceConfigKeys.TAX_RATES).get(d.state().trim().toUpperCase(Locale.ROOT));
        return v == null ? dflt : new BigDecimal(v.toString());
    }
}
