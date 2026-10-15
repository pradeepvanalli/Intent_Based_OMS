package ai.intentoms.monitoring.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Deploy-time settings of the control tower module (ARCHITECTURE §2 "Config").
 *
 * @param enabled                  false disables the scheduled sweeps (tests drive {@code sweepOnce()} directly)
 * @param riskSweepMs              interval of the breach-risk sweep (FR-MON-02); default 60000
 * @param stuckSweepMs             interval of the stuck-order sweep (FR-MON-05); default 60000
 * @param sweepBatch               maximum open orders examined per tenant per sweep; default 500
 * @param pipelineCacheMs          pipeline view cache per tenant+filter (FR-MON-01 freshness budget is 5 s); default 3000
 * @param terminalStatuses         header statuses that take an order out of the open pipeline
 * @param defaultCarrierOnTimeRate carrier on-time rate used when {@code carrier.on_time_rate_7d} is not configured; default 0.94
 */
@ConfigurationProperties(prefix = "oms.monitoring")
public record MonitoringProperties(Boolean enabled, long riskSweepMs, long stuckSweepMs, int sweepBatch, long pipelineCacheMs,
                                   List<String> terminalStatuses, Double defaultCarrierOnTimeRate) {
    public static final List<String> DEFAULT_TERMINAL = List.of("delivered", "cancelled", "picked_up", "refunded", "closed");

    public MonitoringProperties {
        if (enabled == null) enabled = true;
        if (riskSweepMs <= 0) riskSweepMs = 60_000;
        if (stuckSweepMs <= 0) stuckSweepMs = 60_000;
        if (sweepBatch <= 0) sweepBatch = 500;
        if (pipelineCacheMs <= 0) pipelineCacheMs = 3_000;
        if (terminalStatuses == null || terminalStatuses.isEmpty()) terminalStatuses = DEFAULT_TERMINAL;
        terminalStatuses = List.copyOf(terminalStatuses);
        if (defaultCarrierOnTimeRate == null || defaultCarrierOnTimeRate <= 0 || defaultCarrierOnTimeRate > 1) defaultCarrierOnTimeRate = 0.94;
    }

    public boolean isEnabled() { return enabled; }
}
