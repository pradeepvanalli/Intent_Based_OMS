package ai.intentoms.orchestration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Deploy-time settings of the orchestration module ({@code oms.orchestration.*}). Tenants may override the fulfilment
 * timeout at runtime through {@code tenant_config orchestration.fulfilment_timeout} (see {@link OrchestrationConfigKeys}).
 *
 * @param fulfilmentTimeout how long an order waits for WMS events before an exception is raised (FR-WF-05, FR-MON-05)
 * @param deliveryTimeout   how long a shipped order waits for the carrier's delivery scan
 * @param pickupWindow      how long a BOPIS order stays ready for pickup before it is cancelled ({@code timer.pickup_window_expired})
 */
@ConfigurationProperties(prefix = "oms.orchestration")
public record OrchestrationProperties(@DefaultValue("P5D") Duration fulfilmentTimeout, @DefaultValue("P14D") Duration deliveryTimeout,
                                      @DefaultValue("P5D") Duration pickupWindow) {
    public OrchestrationProperties {
        if (fulfilmentTimeout == null || fulfilmentTimeout.isNegative() || fulfilmentTimeout.isZero()) fulfilmentTimeout = Duration.ofDays(5);
        if (deliveryTimeout == null || deliveryTimeout.isNegative() || deliveryTimeout.isZero()) deliveryTimeout = Duration.ofDays(14);
        if (pickupWindow == null || pickupWindow.isNegative() || pickupWindow.isZero()) pickupWindow = Duration.ofDays(5);
    }
}
