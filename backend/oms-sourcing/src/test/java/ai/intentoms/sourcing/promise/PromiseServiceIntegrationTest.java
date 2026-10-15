package ai.intentoms.sourcing.promise;

import ai.intentoms.sourcing.NodeRegistry;
import ai.intentoms.sourcing.PromiseService;
import ai.intentoms.sourcing.SourcingTestBase;
import ai.intentoms.sourcing.model.Promise;
import ai.intentoms.sourcing.model.PromiseRequest;
import ai.intentoms.sourcing.model.SimulationContext;
import ai.intentoms.sourcing.model.SimulationOverrides;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-SRC-05 / FR-SRC-06 promise: determinism, cache hits, trade-offs, p95 latency (NFR-02). */
class PromiseServiceIntegrationTest extends SourcingTestBase {
    @Autowired PromiseService promise;
    @Autowired DefaultPromiseService impl;
    @Autowired NodeRegistry nodes;

    static PromiseRequest req(String sku, int qty, String zip, String state, String method) {
        return new PromiseRequest(List.of(new PromiseRequest.PromiseLine(sku, qty)), zip, state, "web", method);
    }

    @Test
    void promiseComesFromTheNearestStockedNodeWithAlternativesAndCaches() {
        stock("TEE-BLK-M", "DC-NJ", 50); stock("TEE-BLK-M", "DC-NV", 50);
        Promise p = promise.promise(req("TEE-BLK-M", 2, "10001", "NY", "ground"));
        assertThat(p.fromNodeId()).isEqualTo("DC-NJ");
        assertThat(p.date()).isEqualTo(LocalDate.of(2026, 10, 7));              // zone 1 → 1 day from Tuesday
        assertThat(p.onTimeProbability()).isGreaterThan(0.9);
        assertThat(p.fromCache()).isFalse();
        assertThat(p.alternatives()).extracting(Promise.PromiseOption::shipMethod).contains("two_day", "next_day").doesNotContain("ground");
        Promise.PromiseOption nextDay = p.alternatives().stream().filter(a -> a.shipMethod().equals("next_day")).findFirst().orElseThrow();
        assertThat(nextDay.costDelta().isPositive()).isTrue();
        assertThat(nextDay.date()).isEqualTo(p.date());

        Promise again = promise.promise(req("TEE-BLK-M", 2, "10001-4321", "NY", "GROUND"));  // same key: ZIP3 + case-insensitive method
        assertThat(again.fromCache()).isTrue();
        assertThat(again.date()).isEqualTo(p.date());
        assertThat(impl.cacheStats().get("hitCount")).isEqualTo(1L);
    }

    @Test
    void multiSkuPromiseIsTheLatestOfTheBestNodesAndBackorderWhenOutOfStock() {
        stock("TEE-BLK-M", "DC-NJ", 50);
        stock("HOOD-GRY-M", "DC-NV", 50);
        Promise split = promise.promise(new PromiseRequest(List.of(new PromiseRequest.PromiseLine("TEE-BLK-M", 1), new PromiseRequest.PromiseLine("HOOD-GRY-M", 1)), "10001", "NY", "web", "ground"));
        assertThat(split.fromNodeId()).isEqualTo("DC-NV");                        // the slower leg sets the promise
        assertThat(split.date()).isAfter(LocalDate.of(2026, 10, 7));

        Promise none = promise.promise(req("DRONE-MINI", 1, "10001", "NY", "ground"));
        assertThat(none.fromNodeId()).isNull();
        assertThat(none.onTimeProbability()).isEqualTo(0.5);
        assertThat(none.date()).isEqualTo(LocalDate.of(2026, 10, 20));          // 14-day backorder lead
        assertThat(none.alternatives()).isEmpty();
    }

    @Test
    void unknownZipUsesStateCentroidAndPickupNeedsBopis() {
        stock("TEE-BLK-M", "DC-NJ", 50); stock("TEE-BLK-M", "ST-CHI-01", 5);
        Promise byState = promise.promise(req("TEE-BLK-M", 1, null, "IL", "ground"));
        assertThat(byState.fromNodeId()).isEqualTo("ST-CHI-01");
        Promise pickup = promise.promise(req("TEE-BLK-M", 1, "60611", "IL", "pickup"));
        assertThat(pickup.fromNodeId()).isEqualTo("ST-CHI-01");
        assertThat(pickup.date()).isEqualTo(LocalDate.of(2026, 10, 6));          // before the 14:00 cutoff → today
        assertThat(pickup.alternatives()).isEmpty();
    }

    @Test
    void simulationOverridesBypassTheCacheAndCloseNodes() {
        stock("TEE-BLK-M", "DC-NJ", 50); stock("TEE-BLK-M", "DC-TX", 50);
        Promise live = promise.promise(req("TEE-BLK-M", 1, "10001", "NY", "ground"));
        assertThat(live.fromNodeId()).isEqualTo("DC-NJ");
        Promise sim = SimulationContext.with(new SimulationOverrides(Set.of("DC-NJ"), true, 0), () -> promise.promise(req("TEE-BLK-M", 1, "10001", "NY", "ground")));
        assertThat(sim.fromNodeId()).isEqualTo("DC-TX");
        assertThat(sim.fromCache()).isFalse();
        assertThat(sim.date()).isAfter(live.date().plusDays(1));
        assertThat(sim.onTimeProbability()).isLessThan(live.onTimeProbability() - 0.1);
        assertThat(promise.promise(req("TEE-BLK-M", 1, "10001", "NY", "ground")).fromNodeId()).isEqualTo("DC-NJ"); // cache untouched
    }

    @Test
    void p95LatencyOver200CallsStaysUnder300Ms() {
        String[] skus = {"TEE-BLK-M", "HOOD-GRY-M", "JEAN-IND-32", "SNK-RUN-10", "BOOT-LTH-9", "JKT-DWN-L", "BAG-TOTE", "CAP-NVY", "SOCK-3PK", "CNDL-SOY"};
        for (String sku : skus) { stock(sku, "DC-NJ", 100); stock(sku, "DC-TX", 100); stock(sku, "ST-LA-02", 20); }
        String[] zips = {"10001", "60611", "75201", "90036", "30303", "98101", "33101", "80202", "02108", "19103"};
        promise.promise(req(skus[0], 1, zips[0], null, "ground")); // warm the node/rate caches once
        List<Long> latencies = new ArrayList<>(200);
        int hits = 0;
        for (int i = 0; i < 200; i++) {
            long t0 = System.nanoTime();
            Promise p = promise.promise(req(skus[i % skus.length], 1, zips[(i / 10) % 4], null, i % 2 == 0 ? "two_day" : "ground")); // 40 distinct keys → misses then hits
            latencies.add((System.nanoTime() - t0) / 1_000_000);
            if (p.fromCache()) hits++;
        }
        long[] sorted = latencies.stream().mapToLong(Long::longValue).sorted().toArray();
        long p95 = sorted[(int) Math.ceil(0.95 * sorted.length) - 1];
        assertThat(p95).as("p95 latency ms over 200 calls (%s)", Arrays.toString(sorted)).isLessThan(300);
        assertThat(hits).isGreaterThan(0);
    }
}
