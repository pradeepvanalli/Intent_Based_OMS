package ai.intentoms.simulation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Deploy-time settings for the simulation module ({@code oms.simulation.*}; FR-SIM-02).
 *
 * @param pageSize           orders fetched per page while a batch job walks the date range
 * @param commitEvery        number of simulated orders between two progressive result commits
 * @param defaultSampleLimit orders simulated when the request does not set {@code sampleLimit}
 * @param maxSampleLimit     hard ceiling on {@code sampleLimit} so one job cannot replay a whole year
 * @param perOrderSample     per-order rows kept in {@code results.perOrderSample}
 * @param ledgerEventLimit   ceiling on ledger events loaded per order when rebuilding from the ledger
 */
@ConfigurationProperties(prefix = "oms.simulation")
public record SimulationProperties(Integer pageSize, Integer commitEvery, Integer defaultSampleLimit, Integer maxSampleLimit, Integer perOrderSample, Integer ledgerEventLimit) {
    public SimulationProperties {
        if (pageSize == null || pageSize <= 0) pageSize = 100;
        if (commitEvery == null || commitEvery <= 0) commitEvery = 25;
        if (defaultSampleLimit == null || defaultSampleLimit <= 0) defaultSampleLimit = 500;
        if (maxSampleLimit == null || maxSampleLimit <= 0) maxSampleLimit = 5000;
        if (perOrderSample == null || perOrderSample <= 0) perOrderSample = 50;
        if (ledgerEventLimit == null || ledgerEventLimit <= 0) ledgerEventLimit = 10_000;
    }

    /** Effective sample limit for a request: the default when absent, clamped to the maximum. */
    public int sampleLimit(Integer requested) {
        int n = requested == null || requested <= 0 ? defaultSampleLimit : requested;
        return Math.min(n, maxSampleLimit);
    }
}
