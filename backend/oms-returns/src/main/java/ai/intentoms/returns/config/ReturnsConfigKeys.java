package ai.intentoms.returns.config;

import java.util.List;

/**
 * {@code tenant_config} keys read by the returns module (design principle 8: business users change these at runtime).
 * Defaults apply when a tenant has not set the key.
 */
public final class ReturnsConfigKeys {
    private ReturnsConfigKeys() {}

    /** Return window in days when the order type does not define {@code returnWindowDays} (default 30). */
    public static final String WINDOW_DAYS = "returns.window_days";
    /** Per order-type window override: {@code returns.window_days.<orderType>}. */
    public static final String WINDOW_DAYS_PREFIX = "returns.window_days.";
    /** Whether quantity still {@code shipped} (in transit) may be returned (default false). */
    public static final String ALLOW_IN_TRANSIT = "returns.allow_in_transit";
    /** Restocking fee as a fraction of returned merchandise (default 0.0). */
    public static final String RESTOCKING_FEE_PCT = "returns.restocking_fee_pct";
    /** Conditions the restocking fee applies to (default [damaged]). */
    public static final String RESTOCKING_FEE_CONDITIONS = "returns.restocking_fee_conditions";
    /** Conditions acceptable for a refund after inspection (default [new, like_new, damaged]). */
    public static final String REFUNDABLE_CONDITIONS = "returns.refundable_conditions";
    /** Whether the shipping charge share is refunded (default false). */
    public static final String REFUND_SHIPPING = "returns.refund_shipping";
    /** Refunds above this USD amount always go to the refund_approvals queue first (T3 money-moving; default 500). */
    public static final String REFUND_REQUIRES_APPROVAL_ABOVE_USD = "returns.refund_requires_approval_above_usd";
    /** Estimated return shipping label cost in USD (default 8.5). */
    public static final String RETURN_SHIPPING_COST_USD = "returns.return_shipping_cost_usd";
    /** Estimated receiving / inspection handling cost in USD (default 2.0). */
    public static final String RETURN_HANDLING_COST_USD = "returns.return_handling_cost_usd";
    /** Resale recovery as a fraction of unit price for an item returned in new condition (default 0.4). */
    public static final String RECOVERY_RATE = "returns.recovery_rate";
    /** Master switch for keep-it offers (default true). */
    public static final String KEEP_IT_ENABLED = "returns.keep_it_enabled";
    /** Customer refs flagged by the risk team; surfaces as {@code flagged_customer} in customer.history.flags (FR-RET-07). */
    public static final String FLAGGED_CUSTOMERS = "returns.flagged_customers";
    /** Returns in 12 months at or above which a customer is tagged {@code serial_returner} (default 6). */
    public static final String SERIAL_RETURNER_THRESHOLD = "returns.serial_returner_threshold";
    /** Not-received claims in 12 months at or above which a customer is tagged {@code repeated_not_received_claims} (default 2). */
    public static final String NOT_RECEIVED_CLAIMS_THRESHOLD = "returns.not_received_claims_threshold";
    /** Override of the reason taxonomy keys (default: the return_reason decision options + other). */
    public static final String REASON_TAXONOMY = "returns.reason_taxonomy";

    public static final int DEFAULT_WINDOW_DAYS = 30;
    public static final double DEFAULT_RETURN_SHIPPING_COST_USD = 8.5;
    public static final double DEFAULT_RETURN_HANDLING_COST_USD = 2.0;
    public static final double DEFAULT_RECOVERY_RATE = 0.4;
    public static final double DEFAULT_REFUND_APPROVAL_THRESHOLD_USD = 500.0;
    public static final int DEFAULT_SERIAL_RETURNER_THRESHOLD = 6;
    public static final int DEFAULT_NOT_RECEIVED_CLAIMS_THRESHOLD = 2;
    public static final List<String> DEFAULT_REFUNDABLE_CONDITIONS = List.of("new", "like_new", "damaged");
    public static final List<String> DEFAULT_RESTOCKING_FEE_CONDITIONS = List.of("damaged");
    public static final List<String> DEFAULT_REASON_TAXONOMY = List.of("wrong_size", "wrong_item", "defective", "not_as_described", "changed_mind", "arrived_late", "not_received", "other");
}
