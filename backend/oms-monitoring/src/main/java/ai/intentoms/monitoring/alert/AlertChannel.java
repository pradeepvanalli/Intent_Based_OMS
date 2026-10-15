package ai.intentoms.monitoring.alert;

import ai.intentoms.platform.config.TenantConfigStore;

/** One delivery channel (FR-MON-06). Implementations read their endpoint from tenant config and must not swallow failures (the service records them). */
public interface AlertChannel {
    String name();

    /** Whether the tenant has configured this channel for the alert (PagerDuty additionally requires severity critical). */
    boolean configured(TenantConfigStore config, Alert alert);

    void send(TenantConfigStore config, Alert alert);
}
