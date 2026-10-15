package ai.intentoms.simulation.batch;

import ai.intentoms.order.model.Order;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.simulation.SimulationTestBase;
import ai.intentoms.simulation.synthetic.SyntheticOrderGenerator;
import ai.intentoms.simulation.synthetic.SyntheticSpec;
import ai.intentoms.simulation.whatif.ScenarioOverrides;
import ai.intentoms.simulation.whatif.WhatIfService;
import ai.intentoms.order.capture.CaptureRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FR-SIM-02/03/05 against PostgreSQL: batch job lifecycle and metrics, what-if helpers, synthetic capture. */
class BatchSimulationIntegrationTest extends SimulationTestBase {
    @Autowired BatchSimulationService batches;
    @Autowired WhatIfService whatIf;
    @Autowired SyntheticOrderGenerator synthetic;

    @SuppressWarnings("unchecked")
    @Test
    void closingTheChosenNodeChangesCostInTheCompletedJob() {
        Instant from = Instant.now().minus(Duration.ofHours(1));
        for (String note : List.of("Please ship everything together in one delivery", "Send each item as soon as it is ready", "gift for my sister")) sourcedOrder(note);
        Instant to = Instant.now().plus(Duration.ofHours(1));

        SimulationJob submitted = batches.submit(new BatchRequest(from, to, ScenarioOverrides.nodeClosure(Set.of("DC-NJ")), 10, "close NJ"));
        assertThat(submitted.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(submitted.id()).startsWith("sim_");
        assertThat(submitted.submittedBy()).isEqualTo("user:tester");
        assertThat(submitted.sampleLimit()).isEqualTo(10);
        assertThat(batches.await(submitted.id(), Duration.ofSeconds(60))).isTrue();

        SimulationJob job = batches.get(submitted.id());
        assertThat(job.status()).as(job.error()).isEqualTo(JobStatus.COMPLETED);
        assertThat(job.progress()).isEqualTo(1.0);
        assertThat(job.finishedAt()).isNotNull();
        assertThat(job.completedAt()).isEqualTo(job.finishedAt());
        assertThat(job.startedAt()).isNotNull();
        assertThat(job.overrides().closedNodes()).containsExactly("DC-NJ");
        Map<String, Object> results = job.results();
        assertThat(results).isNotNull();
        Map<String, Object> baseline = (Map<String, Object>) results.get("baseline"), simulated = (Map<String, Object>) results.get("simulated"), delta = (Map<String, Object>) results.get("delta");
        assertThat(baseline).containsEntry("orders", 3).containsEntry("sourcedOrders", 3).containsEntry("unsourcedOrders", 0);
        assertThat(simulated).containsEntry("orders", 3).containsEntry("sourcedOrders", 3);
        assertThat(((Number) baseline.get("cost")).doubleValue()).isPositive();
        assertThat(((Number) simulated.get("cost")).doubleValue()).isNotEqualTo(((Number) baseline.get("cost")).doubleValue());
        assertThat(((Number) delta.get("cost")).doubleValue()).isNotZero();
        assertThat(((Number) baseline.get("onTimeRate")).doubleValue()).isBetween(0.0, 1.0);
        assertThat(((Number) baseline.get("margin")).doubleValue()).isPositive();
        assertThat(((Number) baseline.get("touchlessRate")).doubleValue()).isBetween(0.0, 1.0);
        assertThat(results).containsEntry("processed", 3).containsEntry("partial", false).containsEntry("failedOrders", 0).containsEntry("blockedTransitions", 0);
        assertThat(results).containsKeys("orders", "costPerOrder", "baselineCostPerOrder", "splitRate", "baselineSplitRate", "onTimeRate", "baselineOnTimeRate", "marginRatio",
            "baselineMarginRatio", "touchlessRate", "baselineTouchlessRate", "escalations", "baselineEscalations");
        List<Map<String, Object>> sample = (List<Map<String, Object>>) results.get("perOrderSample");
        assertThat(sample).hasSize(3).allSatisfy(row -> {
            assertThat((List<String>) row.get("nodesBefore")).containsExactly("DC-NJ");
            assertThat((List<String>) row.get("nodesAfter")).isNotEmpty().doesNotContain("DC-NJ");
            assertThat(row).containsKeys("costBefore", "costAfter", "packagesBefore", "packagesAfter", "flippedDecisions");
        });

        // Listing is newest first and cancelling a finished job is a conflict.
        assertThat(batches.list(0, 10).items()).extracting(SimulationJob::id).contains(job.id());
        assertThatThrownBy(() -> batches.cancel(job.id())).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> batches.submit(new BatchRequest(to, from, null, null, null))).isInstanceOf(ValidationException.class);
    }

    @SuppressWarnings("unchecked")
    @Test
    void emptyRangeCompletesWithZeroOrdersAndNoNaN() {
        SimulationJob job = batches.submit(BatchRequest.of(Instant.parse("2000-01-01T00:00:00Z"), Instant.parse("2000-01-02T00:00:00Z"), ScenarioOverrides.carrierOutage(List.of("ups"))));
        assertThat(batches.await(job.id(), Duration.ofSeconds(30))).isTrue();
        SimulationJob done = batches.get(job.id());
        assertThat(done.status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(done.progress()).isEqualTo(1.0);
        assertThat(done.overrides().carrierOutage()).isTrue();
        assertThat(done.overrides().outageCarriers()).containsExactly("ups");
        Map<String, Object> baseline = (Map<String, Object>) done.results().get("baseline");
        assertThat(baseline).containsEntry("orders", 0).containsEntry("splitRate", 0.0).containsEntry("touchlessRate", 0.0);
    }

    @SuppressWarnings("unchecked")
    @Test
    void whatIfTariffChangeSubmitsALabelledJobThatCompletes() {
        Instant from = Instant.now().minus(Duration.ofHours(1));
        sourcedOrder("Please ship everything together in one delivery");
        sourcedOrder("gift for my sister");
        Instant to = Instant.now().plus(Duration.ofHours(1));

        SimulationJob job = whatIf.tariffChange(0.25, from, to);
        assertThat(job.kind()).isEqualTo(WhatIfService.TARIFF_CHANGE);
        assertThat(job.label()).isEqualTo("Tariff change: +25%");
        assertThat(job.overrides().tariffDelta()).isEqualTo(0.25);
        assertThat(batches.await(job.id(), Duration.ofSeconds(60))).isTrue();
        SimulationJob done = batches.get(job.id());
        assertThat(done.status()).as(done.error()).isEqualTo(JobStatus.COMPLETED);
        Map<String, Object> baseline = (Map<String, Object>) done.results().get("baseline"), simulated = (Map<String, Object>) done.results().get("simulated");
        assertThat(baseline).containsEntry("orders", 2);
        assertThat(simulated).containsEntry("orders", 2).containsEntry("sourcedOrders", 2);
        // Domestic orders carry no landed cost, so a tariff change leaves cost untouched while the job still runs end to end.
        assertThat(((Number) simulated.get("cost")).doubleValue()).isEqualTo(((Number) baseline.get("cost")).doubleValue());

        SimulationJob closure = whatIf.nodeClosure("DC-TX", from, to);
        assertThat(closure.kind()).isEqualTo(WhatIfService.NODE_CLOSURE);
        assertThat(closure.overrides().closedNodes()).containsExactly("DC-TX");
        assertThat(batches.await(closure.id(), Duration.ofSeconds(60))).isTrue();
        assertThat(batches.get(closure.id()).status()).isEqualTo(JobStatus.COMPLETED);
        assertThatThrownBy(() -> whatIf.nodeClosure("NOPE", from, to)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> whatIf.tariffChange(-1.5, from, to)).isInstanceOf(ValidationException.class);
        assertThat(batches.list(0, 10).items()).extracting(SimulationJob::kind).contains(WhatIfService.TARIFF_CHANGE, WhatIfService.NODE_CLOSURE);
    }

    @Test
    void syntheticOrdersCaptureThroughTheOrderService() {
        SyntheticSpec spec = new SyntheticSpec(5, Map.of("web", 0.7, "marketplace", 0.3), Map.of("standard_ship", 1.0), List.of("TEE-BLK-M", "CAP-NVY", "HOOD-GRY-M", "SOCK-3PK"),
            List.of("10001|New York|NY", "60611|Chicago|IL"), null, 99L, 2, 2, 0.5, "SYNT");
        List<CaptureRequest> requests = synthetic.generate(spec);
        assertThat(requests).hasSize(5);
        assertThat(requests.get(0).lines().get(0).unitPrice().amount()).isEqualByComparingTo(requests.get(0).lines().get(0).sku().equals("TEE-BLK-M") ? "28.00"
            : requests.get(0).lines().get(0).sku().equals("CAP-NVY") ? "32.00" : requests.get(0).lines().get(0).sku().equals("HOOD-GRY-M") ? "68.00" : "36.00");
        for (CaptureRequest r : requests) {
            Order o = orders.capture(r, null);
            assertThat(o.id()).startsWith("ord_");
            assertThat(o.channelOrderId()).isEqualTo(r.channelOrderId());
            assertThat(o.lines()).hasSameSizeAs(r.lines());
            assertThat(o.extensions()).containsEntry("synthetic", true);
        }
        assertThat(jdbc.sql("select count(*) from orders where tenant_id = :t and channel_order_id like 'SYNT-99-%'").param("t", tenant).query(Long.class).single()).isEqualTo(5L);
    }
}
