package ai.intentoms.finance.config;

import ai.intentoms.platform.config.ConfigKeys;

/**
 * Finance-specific {@code tenant_config} keys. Cross-module keys (margin floor, the tenant-wide refund and
 * appeasement velocity caps) are defined once in {@link ConfigKeys} and referenced from here.
 */
public final class FinanceConfigKeys {
    private FinanceConfigKeys() {}

    // ---- GR-05 velocity caps and circuit breaker -------------------------------------------------------------
    /** Tenant-wide refund USD per rolling hour (shared constant). */
    public static final String REFUND_CAP_TENANT = ConfigKeys.REFUND_VELOCITY_CAP_USD_PER_HOUR;
    /** Tenant-wide appeasement USD per rolling hour (shared constant). */
    public static final String APPEASEMENT_CAP_TENANT = ConfigKeys.APPEASEMENT_VELOCITY_CAP_USD_PER_HOUR;
    /** Per-actor (user / role member) refund USD per rolling hour. */
    public static final String REFUND_CAP_PER_ACTOR = "guardrail.refund_velocity_usd_per_hour_per_actor";
    /** Per-actor appeasement USD per rolling hour. */
    public static final String APPEASEMENT_CAP_PER_ACTOR = "guardrail.appeasement_velocity_usd_per_hour_per_actor";
    /** Per-agent refund USD per rolling hour (agent-initiated refunds, FR-AGT-04). */
    public static final String REFUND_CAP_PER_AGENT = "guardrail.refund_velocity_usd_per_hour_per_agent";
    /** Per-agent appeasement USD per rolling hour. */
    public static final String APPEASEMENT_CAP_PER_AGENT = "guardrail.appeasement_velocity_usd_per_hour_per_agent";
    /** ISO-8601 instant until which the spend circuit breaker is open; absent or in the past = closed. */
    public static final String BREAKER_OPEN_UNTIL = "guardrail.breaker_open_until";

    // ---- FR-FIN-02 tax ---------------------------------------------------------------------------------------
    /** Map of destination state → rate (fraction), e.g. {"CA": 0.0725}. */
    public static final String TAX_RATES = "tax.rates";
    /** Rate applied when the destination state has no entry in {@link #TAX_RATES}. */
    public static final String TAX_DEFAULT_RATE = "tax.default_rate";
    /** Whether shipping charges are taxable (default false). */
    public static final String TAX_SHIPPING_TAXABLE = "tax.shipping_taxable";

    // ---- FR-FIN-03 compliance --------------------------------------------------------------------------------
    /** List of ZIP codes (or ZIP prefixes) the tenant will not ship to. */
    public static final String COMPLIANCE_RESTRICTED_ZIPS = "compliance.restricted_zips";
    /** Map of category → list of states where that category may not ship, e.g. {"alcohol": ["UT","MS"]}. */
    public static final String COMPLIANCE_RESTRICTED_STATES = "compliance.restricted_states";
    /** Extra rules (same shape as compliance.yaml entries) merged with the compliance_rule table. */
    public static final String COMPLIANCE_RULES = "compliance.rules";

    // ---- FR-FIN-04 order P&L ---------------------------------------------------------------------------------
    /** Variable payment fee as a fraction of net revenue (default 0.029). */
    public static final String PAYMENT_FEE_RATE = "finance.payment_fee_rate";
    /** Fixed payment fee per order in the order currency (default 0.30). */
    public static final String PAYMENT_FEE_FIXED = "finance.payment_fee_fixed";
    /** Returns reserve as a fraction of net revenue (default 0.05). */
    public static final String RETURNS_RESERVE_RATE = "finance.returns_reserve_rate";
}
