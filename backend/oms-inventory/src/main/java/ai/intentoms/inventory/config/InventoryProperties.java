package ai.intentoms.inventory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Deploy-time settings.
 *
 * @param networkCacheTtl how long a network ATP figure may be served from memory (NFR-01); writes invalidate eagerly
 * @param expirySweepEnabled whether this instance runs the reservation TTL sweep (FR-INV-02)
 */
@ConfigurationProperties(prefix = "oms.inventory")
public record InventoryProperties(Duration networkCacheTtl, Boolean expirySweepEnabled) {
    public InventoryProperties {
        if (networkCacheTtl == null) networkCacheTtl = Duration.ofSeconds(2);
        if (expirySweepEnabled == null) expirySweepEnabled = true;
    }
}
