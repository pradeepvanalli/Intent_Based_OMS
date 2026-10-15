package ai.intentoms.orchestration.config;

/** Orchestration {@code tenant_config} keys (runtime, per tenant). */
public final class OrchestrationConfigKeys {
    private OrchestrationConfigKeys() {}
    /** ISO-8601 duration an order may sit in {@code await_fulfilment} before a {@code fulfilment_timeout} exception (default {@code oms.orchestration.fulfilment-timeout}). */
    public static final String FULFILMENT_TIMEOUT = "orchestration.fulfilment_timeout";
    /** ISO-8601 duration a shipped order waits for a delivery scan (default {@code oms.orchestration.delivery-timeout}). */
    public static final String DELIVERY_TIMEOUT = "orchestration.delivery_timeout";
    /** ISO-8601 duration a BOPIS order stays ready for pickup (default {@code oms.orchestration.pickup-window}). */
    public static final String PICKUP_WINDOW = "orchestration.pickup_window";
    /** Shared secret for inbound WMS / carrier / inventory webhooks; when set {@code X-Webhook-Signature} is mandatory. */
    public static final String WEBHOOK_INBOUND_SECRET = "webhooks.inbound_secret";
}
