package ai.intentoms.finance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Deploy-time settings for the finance module ({@code oms.finance.*}). Runtime, per-tenant settings (tax rates,
 * velocity caps, fee rates) live in {@code tenant_config}; see {@link FinanceConfigKeys}.
 *
 * @param psp     payment gateway implementation: {@code mock} (default) or {@code stripe} (FR-FIN-01)
 * @param tax     tax engine implementation: {@code flat} (default) or {@code avalara} (FR-FIN-02)
 * @param mock    settings for the deterministic mock gateway
 * @param stripe  Stripe PaymentIntents settings
 * @param avalara Avalara AvaTax settings
 */
@ConfigurationProperties(prefix = "oms.finance")
public record FinanceProperties(
    @DefaultValue("mock") String psp,
    @DefaultValue("flat") String tax,
    @DefaultValue Mock mock,
    @DefaultValue Stripe stripe,
    @DefaultValue Avalara avalara
) {
    /** @param authTtl how long a mock authorisation stays valid before it must be re-authorised (card networks: ~7 days). */
    public record Mock(@DefaultValue("7d") Duration authTtl) {}

    /**
     * @param apiKey   secret key ({@code sk_live_...} / {@code sk_test_...}); injected from a secret store
     * @param baseUrl  API base, overridable for a local stripe-mock
     * @param authTtl  authorisation validity for uncaptured PaymentIntents (Stripe: 7 days for most card networks)
     * @param timeout  HTTP timeout per call
     */
    public record Stripe(
        @DefaultValue("") String apiKey,
        @DefaultValue("https://api.stripe.com") String baseUrl,
        @DefaultValue("7d") Duration authTtl,
        @DefaultValue("10s") Duration timeout
    ) {}

    /**
     * @param baseUrl     AvaTax REST base (sandbox: https://sandbox-rest.avatax.com)
     * @param accountId   account id used for HTTP basic auth
     * @param licenseKey  license key used for HTTP basic auth
     * @param companyCode AvaTax company code the transactions are created under
     * @param commit      whether to commit transactions (false = quote only)
     * @param timeout     HTTP timeout per call
     */
    public record Avalara(
        @DefaultValue("https://rest.avatax.com") String baseUrl,
        @DefaultValue("") String accountId,
        @DefaultValue("") String licenseKey,
        @DefaultValue("DEFAULT") String companyCode,
        @DefaultValue("false") boolean commit,
        @DefaultValue("5s") Duration timeout
    ) {}
}
