package ai.intentoms.order.config;

import ai.intentoms.platform.config.ConfigKeys;

/** Order-module {@code tenant_config} keys (FR-CAP-05 pipeline settings, FR-CS-02 change policy, storefront secrets). */
public final class OrderConfigKeys {
    private OrderConfigKeys() {}

    /** Ordered list of validation step names run at capture (FR-CAP-05). */
    public static final String VALIDATION_STEPS = "capture.validation_steps";
    /** boolean, default false: accept lines whose sku is not in the catalogue. */
    public static final String ALLOW_UNKNOWN_SKU = "capture.allow_unknown_sku";
    /** fraction, default 0.25: unit price may deviate this much from the catalogue price before a warning. */
    public static final String PRICE_TOLERANCE = "capture.price_tolerance";
    /** boolean, default false: whether an address change after shipment may be attempted as a carrier intercept (FR-CS-02). */
    public static final String CARRIER_INTERCEPT_ENABLED = "cs.carrier_intercept_enabled";
    /** USD fee charged for a carrier intercept (FR-CS-02). */
    public static final String CARRIER_INTERCEPT_FEE_USD = "cs.carrier_intercept_fee_usd";
    /** Shopify webhook shared secret; when set every {@code /webhooks/shopify} call must carry a valid HMAC. */
    public static final String SHOPIFY_WEBHOOK_SECRET = "shopify.webhook_secret";
    /** Contribution-margin floor shared with sourcing (FR-SRC-04). */
    public static final String MARGIN_FLOOR = ConfigKeys.MARGIN_FLOOR;
    /** Map channel → fulfilment policy used when intent is unclear (FR-CAP-06). */
    public static final String CHANNEL_DEFAULT_POLICY = ConfigKeys.CHANNEL_DEFAULT_POLICY;

    /** Default pipeline when the tenant has not configured {@link #VALIDATION_STEPS}. */
    public static final java.util.List<String> DEFAULT_VALIDATION_STEPS = java.util.List.of("schema", "item_price_sanity", "compliance", "address", "payment_status", "agent_provenance");
}
