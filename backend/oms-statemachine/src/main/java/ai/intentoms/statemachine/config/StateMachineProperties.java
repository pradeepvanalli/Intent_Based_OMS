package ai.intentoms.statemachine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Deploy-time settings for the state machine module.
 *
 * @param sweepMs    interval of the SLA-timer / hold-expiry sweep (FR-SM-07, FR-SM-08); default 5000
 * @param sweepBatch maximum due timers (or expired holds) processed per sweep; default 500
 * @param cacheSize  maximum parsed model versions kept in memory per node; default 1000
 */
@ConfigurationProperties(prefix = "oms.statemachine")
public record StateMachineProperties(long sweepMs, int sweepBatch, int cacheSize) {
    public StateMachineProperties {
        if (sweepMs <= 0) sweepMs = 5000;
        if (sweepBatch <= 0) sweepBatch = 500;
        if (cacheSize <= 0) cacheSize = 1000;
    }
}
