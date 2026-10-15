package ai.intentoms.integrations.config;

/** Integrations {@code tenant_config} keys (runtime, per tenant). Values are JSON; strings are written as JSON strings. */
public final class IntegrationsConfigKeys {
    private IntegrationsConfigKeys() {}

    /** {@code marketplace.<name>.webhook_secret}: HMAC-SHA256 secret for {@code POST /webhooks/marketplace/<name>}; when set the signature header is mandatory. */
    public static String marketplaceWebhookSecret(String marketplace) { return "marketplace." + marketplace + ".webhook_secret"; }
    /** {@code marketplace.<name>.order_type}: OMS order type for that marketplace's orders (default {@code standard_ship}). */
    public static String marketplaceOrderType(String marketplace) { return "marketplace." + marketplace + ".order_type"; }
    /** {@code marketplace.amazon.watermark}: ISO instant of the last successful SP-API poll. */
    public static final String AMAZON_WATERMARK = "marketplace.amazon.watermark";

    /** {@code notify.default_channel}: {@code email} | {@code sms} when the caller leaves the channel open. */
    public static final String NOTIFY_DEFAULT_CHANNEL = "notify.default_channel";
    /** {@code notify.brand_name}: rendered into the {@code {{brand}}} slot of every template. */
    public static final String NOTIFY_BRAND = "notify.brand_name";

    /** {@code carrier.easypost.webhook_secret}: secret EasyPost signs tracker webhooks with ({@code X-Hmac-Signature}). */
    public static final String EASYPOST_WEBHOOK_SECRET = "carrier.easypost.webhook_secret";

    /** {@code wms.<nodeId>.release_url} / {@code wms.release_url}: where release documents are posted. */
    public static String wmsReleaseUrl(String nodeId) { return "wms." + nodeId + ".release_url"; }
    public static final String WMS_RELEASE_URL = "wms.release_url";
    /** {@code wms.<nodeId>.cancel_url} / {@code wms.cancel_url}: where cancel documents go (defaults to the release URL). */
    public static String wmsCancelUrl(String nodeId) { return "wms." + nodeId + ".cancel_url"; }
    public static final String WMS_CANCEL_URL = "wms.cancel_url";
    /** {@code wms.webhook_secret}: shared secret the release documents are signed with ({@code X-Webhook-Signature}). */
    public static final String WMS_WEBHOOK_SECRET = "wms.webhook_secret";
}
