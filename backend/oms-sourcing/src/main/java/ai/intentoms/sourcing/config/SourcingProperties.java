package ai.intentoms.sourcing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Deploy-time settings ({@code oms.sourcing.*}).
 *
 * @param promise promise cache settings
 */
@ConfigurationProperties(prefix = "oms.sourcing")
public record SourcingProperties(Promise promise) {
    public SourcingProperties {
        if (promise == null) promise = new Promise(null, null, null);
    }

    /**
     * @param cacheTtl     in-memory (Caffeine) promise TTL, default 30 s (FR-SRC-05)
     * @param cacheMaxSize maximum cached promises per instance
     * @param redisUrl     when set, the {@code PromiseCacheL2} bean is expected to be a Redis implementation supplied by the
     *                     deployment (this module ships only the SPI and a no-op default; Spring Data Redis is not a dependency)
     */
    public record Promise(Duration cacheTtl, Integer cacheMaxSize, String redisUrl) {
        public Promise {
            if (cacheTtl == null) cacheTtl = Duration.ofSeconds(30);
            if (cacheMaxSize == null) cacheMaxSize = 200_000;
        }
        public boolean l2Configured() { return redisUrl != null && !redisUrl.isBlank(); }
    }
}
