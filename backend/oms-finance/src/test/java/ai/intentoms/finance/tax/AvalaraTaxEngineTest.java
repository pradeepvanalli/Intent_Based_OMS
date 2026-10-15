package ai.intentoms.finance.tax;

import ai.intentoms.finance.config.FinanceProperties;
import ai.intentoms.finance.model.Money;
import ai.intentoms.platform.time.TimeSource;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the AvaTax CreateTransaction request shape and response mapping without any network call (FR-FIN-02). */
class AvalaraTaxEngineTest {

    private AvalaraTaxEngine engine() {
        FinanceProperties props = new FinanceProperties("mock", "avalara", new FinanceProperties.Mock(Duration.ofDays(7)),
            new FinanceProperties.Stripe("", "https://api.stripe.com", Duration.ofDays(7), Duration.ofSeconds(10)),
            new FinanceProperties.Avalara("https://sandbox-rest.avatax.com", "acct", "key", "DEMO", false, Duration.ofSeconds(5)));
        return new AvalaraTaxEngine(RestClient.builder(), props, new TimeSource());
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildsCreateTransactionModel() {
        TaxRequest req = new TaxRequest(List.of(new TaxLine("L1", "SKU-1", Money.usd("100.00"), "P0000000")), Money.usd("9.99"),
            new TaxDestination("CA", "94105", "US", "1 Market St", "San Francisco"), "cust-42");
        Map<String, Object> body = engine().buildCreateTransaction(req);
        assertThat(body).containsEntry("type", "SalesOrder").containsEntry("companyCode", "DEMO").containsEntry("customerCode", "cust-42").containsEntry("currencyCode", "USD");
        Map<String, Object> shipTo = ((Map<String, Map<String, Object>>) body.get("addresses")).get("shipTo");
        assertThat(shipTo).containsEntry("region", "CA").containsEntry("postalCode", "94105").containsEntry("country", "US").containsEntry("line1", "1 Market St");
        List<Map<String, Object>> lines = (List<Map<String, Object>>) body.get("lines");
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).containsEntry("number", "L1").containsEntry("itemCode", "SKU-1").containsEntry("taxCode", "P0000000");
        assertThat((BigDecimal) lines.get(0).get("amount")).isEqualByComparingTo("100.00");
        assertThat(lines.get(1)).containsEntry("number", TaxResult.SHIPPING_KEY).containsEntry("taxCode", AvalaraTaxEngine.FREIGHT_TAX_CODE);
    }

    @Test
    void mapsTransactionModelResponse() {
        TaxRequest req = new TaxRequest(List.of(new TaxLine("L1", "SKU-1", Money.usd("100.00"), null)), null, new TaxDestination("CA", "94105", "US"));
        Map<String, Object> tx = Map.of(
            "totalTax", 8.63,
            "lines", List.of(Map.of("lineNumber", "L1", "tax", 8.63, "details", List.of(Map.of("rate", 0.06), Map.of("rate", 0.0263)))));
        TaxResult r = engine().parse(tx, req);
        assertThat(r.engine()).isEqualTo("avalara");
        assertThat(r.total()).isEqualTo(Money.usd("8.63"));
        assertThat(r.perLine()).containsEntry("L1", Money.usd("8.63"));
        assertThat(r.perLineRates().get("L1")).isEqualByComparingTo("0.0863");
    }
}
