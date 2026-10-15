package ai.intentoms.finance.tax;

import ai.intentoms.finance.config.FinanceProperties;
import ai.intentoms.finance.model.Money;
import ai.intentoms.platform.error.OmsException;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.platform.time.TimeSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Avalara AvaTax implementation of {@link TaxEngine} (FR-FIN-02), active when {@code oms.finance.tax=avalara}.
 * Issues {@code POST /api/v2/transactions/create} with the documented CreateTransactionModel: type
 * {@code SalesOrder} (quote) or {@code SalesInvoice} when {@code oms.finance.avalara.commit=true}, company code,
 * ship-to address, one line per order line (itemCode, taxCode, amount) plus an optional freight line
 * ({@code FR020100}). The response's {@code totalTax} and per-line {@code tax} / summed {@code details[].rate}
 * populate {@link TaxResult}. Authenticates with HTTP basic (account id : license key). Written against the public
 * API contract; not exercised by the automated test-suite because tests must not require network or credentials.
 */
@Component
@ConditionalOnProperty(name = "oms.finance.tax", havingValue = "avalara")
public class AvalaraTaxEngine implements TaxEngine {
    private static final Logger log = LoggerFactory.getLogger(AvalaraTaxEngine.class);
    public static final String NAME = "avalara";
    /** AvaTax system tax code for shipping/freight. */
    static final String FREIGHT_TAX_CODE = "FR020100";

    private final RestClient http;
    private final FinanceProperties.Avalara cfg;
    private final TimeSource time;

    public AvalaraTaxEngine(RestClient.Builder builder, FinanceProperties props, TimeSource time) {
        this.cfg = props.avalara();
        this.time = time;
        var factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(cfg.timeout());
        String basic = Base64.getEncoder().encodeToString((cfg.accountId() + ":" + cfg.licenseKey()).getBytes(StandardCharsets.UTF_8));
        this.http = builder.clone()
            .requestFactory(factory)
            .baseUrl(cfg.baseUrl())
            .defaultHeader("Authorization", "Basic " + basic)
            .defaultHeader("X-Avalara-Client", "IntentOMS;1.0;REST;v2")
            .build();
        if (cfg.accountId().isBlank() || cfg.licenseKey().isBlank()) log.warn("oms.finance.tax=avalara but credentials are empty; AvaTax calls will fail");
    }

    @Override
    public String name() { return NAME; }

    @Override
    public TaxResult calculate(TaxRequest r) {
        Map<String, Object> body = buildCreateTransaction(r);
        try {
            String json = http.post().uri("/api/v2/transactions/create").contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body(String.class);
            return parse(Json.readMap(json), r);
        } catch (RestClientResponseException e) {
            throw new OmsException(HttpStatus.BAD_GATEWAY, "tax_engine_error",
                "AvaTax rejected the transaction: " + e.getStatusCode() + " " + summarise(e.getResponseBodyAsString()), Map.of(), e);
        } catch (ResourceAccessException e) {
            throw new OmsException(HttpStatus.BAD_GATEWAY, "tax_engine_error", "AvaTax unreachable: " + e.getMessage(), Map.of(), e);
        }
    }

    /** Builds the CreateTransactionModel; package-private so the shape can be unit-tested without a network. */
    Map<String, Object> buildCreateTransaction(TaxRequest r) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", cfg.commit() ? "SalesInvoice" : "SalesOrder");
        body.put("companyCode", cfg.companyCode());
        body.put("date", LocalDate.ofInstant(time.now(), ZoneOffset.UTC).toString());
        body.put("customerCode", r.customerCode() == null || r.customerCode().isBlank() ? "GUEST" : r.customerCode());
        body.put("currencyCode", r.currency());
        body.put("commit", cfg.commit());
        body.put("referenceCode", TenantContext.current().orElse("oms"));
        TaxDestination d = r.destination();
        Map<String, Object> shipTo = new LinkedHashMap<>();
        if (d != null) {
            if (d.line1() != null) shipTo.put("line1", d.line1());
            if (d.city() != null) shipTo.put("city", d.city());
            if (d.state() != null) shipTo.put("region", d.state());
            if (d.zip() != null) shipTo.put("postalCode", d.zip());
            shipTo.put("country", d.country() == null ? "US" : d.country());
        }
        body.put("addresses", Map.of("shipTo", shipTo));
        List<Map<String, Object>> lines = new ArrayList<>();
        if (r.lines() != null) {
            for (TaxLine l : r.lines()) {
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("number", l.lineId());
                line.put("quantity", 1);
                line.put("amount", l.amount() == null ? BigDecimal.ZERO : l.amount().amount());
                line.put("itemCode", l.sku());
                if (l.taxCode() != null && !l.taxCode().isBlank()) line.put("taxCode", l.taxCode());
                lines.add(line);
            }
        }
        if (r.shipping() != null && r.shipping().isPositive()) {
            Map<String, Object> freight = new LinkedHashMap<>();
            freight.put("number", TaxResult.SHIPPING_KEY);
            freight.put("quantity", 1);
            freight.put("amount", r.shipping().amount());
            freight.put("itemCode", "SHIPPING");
            freight.put("taxCode", FREIGHT_TAX_CODE);
            lines.add(freight);
        }
        body.put("lines", lines);
        return body;
    }

    /** Maps an AvaTax TransactionModel onto {@link TaxResult}; package-private for unit tests. */
    TaxResult parse(Map<String, Object> tx, TaxRequest r) {
        String ccy = r.currency();
        Map<String, Money> perLine = new LinkedHashMap<>();
        Map<String, BigDecimal> perRate = new LinkedHashMap<>();
        if (tx.get("lines") instanceof List<?> lines) {
            for (Object o : lines) {
                if (!(o instanceof Map<?, ?> line)) continue;
                String number = String.valueOf(line.get("lineNumber"));
                BigDecimal tax = decimal(line.get("tax"));
                BigDecimal rate = BigDecimal.ZERO;
                if (line.get("details") instanceof List<?> details) {
                    for (Object det : details) if (det instanceof Map<?, ?> dm) rate = rate.add(decimal(dm.get("rate")));
                }
                perLine.put(number, new Money(tax, ccy));
                perRate.put(number, rate);
            }
        }
        Money total = new Money(decimal(tx.get("totalTax")), ccy);
        return new TaxResult(total, perLine, perRate, NAME);
    }

    private static BigDecimal decimal(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal b) return b;
        if (o instanceof Number n) return new BigDecimal(n.toString());
        return new BigDecimal(o.toString());
    }

    private static String summarise(String body) {
        if (body == null) return "";
        return body.length() > 300 ? body.substring(0, 300) : body;
    }
}
