package ai.intentoms.finance.tax;

import ai.intentoms.finance.FinanceTestBase;
import ai.intentoms.finance.config.FinanceConfigKeys;
import ai.intentoms.finance.model.Money;
import ai.intentoms.platform.config.TenantConfigStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-FIN-02 flat-rate engine driven by tenant_config. */
class FlatRateTaxEngineIntegrationTest extends FinanceTestBase {
    @Autowired TaxEngine engine;
    @Autowired TenantConfigStore config;

    @Test
    void perStateRatesDefaultRateExemptCodesAndShipping() {
        assertThat(engine.name()).isEqualTo("flat");
        config.set(FinanceConfigKeys.TAX_RATES, Map.of("CA", 0.0725, "NY", 0.08875));
        config.set(FinanceConfigKeys.TAX_DEFAULT_RATE, 0.0);
        TaxRequest ca = new TaxRequest(List.of(new TaxLine("L1", "A", Money.usd("100.00"), null), new TaxLine("L2", "B", Money.usd("50.00"), "EXEMPT")),
            Money.usd("10.00"), new TaxDestination("ca", "94105", "US"));
        TaxResult r = engine.calculate(ca);
        assertThat(r.perLine().get("L1")).isEqualTo(Money.usd("7.25"));
        assertThat(r.perLine().get("L2")).isEqualTo(Money.zero("USD"));
        assertThat(r.perLineRates().get("L1")).isEqualByComparingTo("0.0725");
        assertThat(r.perLineRates().get("L2")).isEqualByComparingTo("0");
        assertThat(r.perLine()).doesNotContainKey(TaxResult.SHIPPING_KEY);
        assertThat(r.total()).isEqualTo(Money.usd("7.25"));

        config.set(FinanceConfigKeys.TAX_SHIPPING_TAXABLE, true);
        TaxResult withShipping = engine.calculate(ca);
        assertThat(withShipping.perLine().get(TaxResult.SHIPPING_KEY)).isEqualTo(Money.usd("0.72")); // 10 × 0.0725 = 0.725 → 0.72 (half-even)
        assertThat(withShipping.total()).isEqualTo(Money.usd("7.25").add(withShipping.perLine().get(TaxResult.SHIPPING_KEY)));

        TaxResult oregon = engine.calculate(new TaxRequest(List.of(new TaxLine("L1", "A", Money.usd("100.00"), null)), null, new TaxDestination("OR", "97201", "US")));
        assertThat(oregon.total()).isEqualTo(Money.zero("USD"));
        config.set(FinanceConfigKeys.TAX_DEFAULT_RATE, 0.05);
        assertThat(engine.calculate(new TaxRequest(List.of(new TaxLine("L1", "A", Money.usd("100.00"), null)), null, new TaxDestination("WA", "98101", "US"))).total())
            .isEqualTo(Money.usd("5.00"));
    }
}
