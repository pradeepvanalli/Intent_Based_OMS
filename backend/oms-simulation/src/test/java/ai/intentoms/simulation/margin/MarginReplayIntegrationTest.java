package ai.intentoms.simulation.margin;

import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.simulation.SimulationTestBase;
import ai.intentoms.simulation.batch.JobStatus;
import ai.intentoms.simulation.batch.SimulationJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Margin Replay against PostgreSQL with the demo tenant seed (nodes, rate card, catalogue, decisions on the heuristic
 * provider): the bundled Shopify sample runs end to end as an {@code import} job, the report has the documented shape, no
 * order is persisted, and the baseline/decision switches change the outcome.
 */
class MarginReplayIntegrationTest extends SimulationTestBase {
    @Autowired MarginReplayService replay;

    @SuppressWarnings("unchecked")
    @Test
    void bundledShopifySampleReplaysUnderAMinuteAndPersistsNoOrders() {
        long ordersBefore = jdbc.sql("select count(*) from orders where tenant_id = :t").param("t", tenant).query(Long.class).single();
        long started = System.nanoTime();
        SimulationJob submitted = replay.submitSample(MarginReplayOptions.defaults());
        assertThat(submitted.kind()).isEqualTo(MarginReplayService.KIND);
        assertThat(submitted.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(submitted.label()).contains("shopify-orders-sample.csv").contains("299 orders");
        assertThat(submitted.from()).isBefore(submitted.to());
        assertThat(replay.await(submitted.id(), Duration.ofSeconds(90))).isTrue();
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        SimulationJob job = replay.get(submitted.id());
        assertThat(job.status()).as(job.error()).isEqualTo(JobStatus.COMPLETED);
        assertThat(job.progress()).isEqualTo(1.0);
        Map<String, Object> results = job.results();
        assertThat(results).containsEntry("kind", "import").containsEntry("partial", false).containsKeys("options", "file", "summary", "topFindings", "byRegion", "assumptions", "perOrder", "markdownSummary");
        Map<String, Object> summary = (Map<String, Object>) results.get("summary");
        Map<String, Object> file = (Map<String, Object>) results.get("file");
        assertThat(file).containsEntry("format", "shopify").containsEntry("orders", 300);

        int orders = (Integer) summary.get("orders"), planned = (Integer) summary.get("planned"), skipped = (Integer) summary.get("skipped");
        assertThat(orders).isEqualTo(300);
        assertThat(planned + skipped).isEqualTo(300);
        assertThat(planned).isGreaterThan(280);
        Map<String, Object> skips = (Map<String, Object>) summary.get("skippedReasons");
        assertThat(skips).containsEntry("cancelled", 1).containsEntry("non-US destination (CA)", 1).containsEntry("non-US destination (GB)", 1);

        double baselineShipping = num(summary, "baselineShippingCost"), intentShipping = num(summary, "intentShippingCost");
        double recovered = num(summary, "marginRecoveredUsd");
        assertThat(baselineShipping).isPositive();
        assertThat(intentShipping).isPositive();
        assertThat(num(summary, "shippingSaved")).isCloseTo(baselineShipping - intentShipping, org.assertj.core.data.Offset.offset(0.02));
        assertThat(recovered).as("Intent OMS should never lose margin against a nearest-node baseline on the whole sample").isPositive();
        assertThat((Integer) summary.get("ordersChanged")).isGreaterThan(20);
        assertThat((Integer) summary.get("storeToDc")).isGreaterThan(10);
        assertThat((Integer) summary.get("ordersBelowFloorFlagged")).isGreaterThanOrEqualTo(3);
        Map<String, Object> intents = (Map<String, Object>) summary.get("intentsDetected");
        assertThat(intents).containsKeys("gift", "together", "urgent");
        assertThat((Integer) summary.get("ordersWithNotes")).isBetween(20, 60);
        assertThat((Integer) summary.get("poBoxOrders")).isGreaterThanOrEqualTo(4);
        assertThat((Integer) summary.get("unknownSkuLines")).isGreaterThan(30);
        assertThat(num(summary, "avgOnTimeBefore")).isBetween(0.5, 1.0);
        assertThat(num(summary, "avgOnTimeAfter")).isBetween(0.5, 1.0);
        assertThat((Integer) summary.get("annualisationDays")).isBetween(28, 31);
        assertThat(num(summary, "estimatedAnnualisedSavings")).isCloseTo(recovered * 365.0 / (Integer) summary.get("annualisationDays"), org.assertj.core.data.Offset.offset(1.0));

        List<String> findings = (List<String>) results.get("topFindings");
        assertThat(findings).isNotEmpty().hasSizeLessThanOrEqualTo(5).allSatisfy(f -> assertThat(f).endsWith("."));
        assertThat(findings.get(0)).contains("contribution margin recovered").contains("a year (estimate)");
        assertThat(findings).anySatisfy(f -> assertThat(f).contains("went to a store because it was nearest"));
        List<Map<String, Object>> regions = (List<Map<String, Object>>) results.get("byRegion");
        assertThat(regions).isNotEmpty().allSatisfy(r -> assertThat(r).containsKeys("state", "region", "orders", "changed", "saved", "baselineCost", "intentCost"));
        assertThat(regions.stream().mapToInt(r -> (Integer) r.get("orders")).sum()).isEqualTo(planned);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) results.get("perOrder");
        assertThat(rows).hasSize(300);
        Map<String, Object> aPlannedRow = rows.stream().filter(r -> !Boolean.TRUE.equals(r.get("skipped"))).findFirst().orElseThrow();
        assertThat(aPlannedRow).containsKeys("orderId", "costBefore", "costAfter", "packagesBefore", "packagesAfter", "nodesBefore", "nodesAfter", "onTimeBefore", "onTimeAfter", "marginBefore", "marginAfter",
            "flippedDecisions", "blockedTransitions", "intent", "belowFloor", "poBox", "changed", "reason", "policy", "state", "region");
        assertThat(rows.stream().filter(r -> "together".equals(r.get("intent"))).findFirst()).isPresent().get().satisfies(r -> assertThat(r.get("policy")).isEqualTo("ship_complete"));
        // A one-line order has no "earliest partial" (falls back to ship_complete); a multi-line urgent order expedites the urgent line separately.
        assertThat(rows.stream().filter(r -> "urgent".equals(r.get("intent")) && (Integer) r.get("lines") >= 2).findFirst()).isPresent().get()
            .satisfies(r -> { assertThat(r.get("policy")).isEqualTo("earliest_partial"); assertThat((Integer) r.get("packagesAfter")).isEqualTo(2); });
        String md = (String) results.get("markdownSummary");
        assertThat(md).contains("# Margin Replay — shopify-orders-sample.csv").contains("| Contribution margin |").contains("## Findings").contains("## Assumptions");
        assertThat((List<String>) results.get("assumptions")).anySatisfy(a -> assertThat(a).contains("assumeStock=true"));
        // Flat fields for the generic Simulation page.
        assertThat(results).containsEntry("orders", planned).containsKeys("costPerOrder", "baselineCostPerOrder", "splitRate", "onTimeRate", "marginRatio", "touchlessRate", "escalations");

        // Side effects: no orders, no plans, no human tasks; decision records only in SIMULATION mode.
        assertThat(jdbc.sql("select count(*) from orders where tenant_id = :t").param("t", tenant).query(Long.class).single()).isEqualTo(ordersBefore);
        assertThat(jdbc.sql("select count(*) from sourcing_plan where tenant_id = :t").param("t", tenant).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from decision_record where tenant_id = :t and subject_type = 'margin_replay' and mode <> 'SIMULATION'").param("t", tenant).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from decision_record where tenant_id = :t and subject_type = 'margin_replay' and mode = 'SIMULATION'").param("t", tenant).query(Long.class).single()).isGreaterThan(10L);
        assertThat(jdbc.sql("select count(*) from decision_escalation where tenant_id = :t").param("t", tenant).query(Long.class).single()).isZero();

        System.out.printf(java.util.Locale.ROOT, "MARGIN_REPLAY_SAMPLE orders=%d planned=%d skipped=%d changed=%s storeToDc=%s shippingBefore=%.2f shippingAfter=%.2f shippingSaved=%.2f marginRecovered=%.2f perOrder=%s "
            + "splitsAvoided=%s splitsAdded=%s belowFloor=%s intents=%s onTime=%.3f->%.3f annualised=%.2f days=%s elapsedMs=%d%n", orders, planned, skipped, summary.get("ordersChanged"), summary.get("storeToDc"),
            baselineShipping, intentShipping, num(summary, "shippingSaved"), recovered, summary.get("marginRecoveredPerOrder"), summary.get("splitsAvoided"), summary.get("splitsAdded"),
            summary.get("ordersBelowFloorFlagged"), intents, num(summary, "avgOnTimeBefore"), num(summary, "avgOnTimeAfter"), num(summary, "estimatedAnnualisedSavings"), summary.get("annualisationDays"), elapsedMs);
        for (String f : findings) System.out.println("FINDING: " + f);
        assertThat(elapsedMs).as("the bundled sample must replay in under a minute").isLessThan(60_000);
    }

    @SuppressWarnings("unchecked")
    @Test
    void singleDcBaselineAndKeywordIntentsAndRealStock() {
        SimulationJob dc = replay.submitSample(new MarginReplayOptions("shopify", true, 0.5, "single_dc:DC-NJ", false));
        assertThat(replay.await(dc.id(), Duration.ofSeconds(90))).isTrue();
        Map<String, Object> results = replay.get(dc.id()).results();
        Map<String, Object> summary = (Map<String, Object>) results.get("summary");
        double recovered = num(summary, "marginRecoveredUsd");
        assertThat(recovered).isPositive();
        assertThat((Integer) summary.get("ordersChanged")).isGreaterThan(100);
        assertThat(((Map<String, Object>) results.get("options"))).containsEntry("baseline", "single_dc:DC-NJ").containsEntry("useDecisions", false);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) results.get("perOrder");
        // Every shipped order leaves from the single DC; pickup orders cannot (a DC has no BOPIS) and fall back to the nearest store.
        assertThat(rows.stream().filter(r -> !Boolean.TRUE.equals(r.get("skipped")) && !"pickup".equals(r.get("shipMethod")))).isNotEmpty().allSatisfy(r -> assertThat((List<String>) r.get("nodesBefore")).contains("DC-NJ"));
        assertThat(rows.stream().filter(r -> "keywords".equals(r.get("intentSource"))).count()).isGreaterThan(15);
        assertThat((List<String>) results.get("assumptions")).anySatisfy(a -> assertThat(a).contains("single-DC")).anySatisfy(a -> assertThat(a).contains("keyword"));
        assertThat((List<String>) results.get("topFindings")).anySatisfy(f -> assertThat(f).contains("DC-NJ"));
        System.out.printf(java.util.Locale.ROOT, "MARGIN_REPLAY_SINGLE_DC planned=%s changed=%s shippingSaved=%.2f marginRecovered=%.2f annualised=%.2f%n", summary.get("planned"), summary.get("ordersChanged"),
            num(summary, "shippingSaved"), recovered, num(summary, "estimatedAnnualisedSavings"));

        // Real ATP: the seed stocks only four SKUs at DC-NJ and DC-TX, so most orders have no stock anywhere and are skipped with that reason.
        SimulationJob stock = replay.submitSample(new MarginReplayOptions(null, false, null, null, false));
        assertThat(replay.await(stock.id(), Duration.ofSeconds(90))).isTrue();
        Map<String, Object> s2 = (Map<String, Object>) replay.get(stock.id()).results().get("summary");
        assertThat((Integer) s2.get("planned")).isBetween(1, 250);
        assertThat(((Map<String, Object>) s2.get("skippedReasons")).keySet()).anySatisfy(k -> assertThat(k).contains("assumeStock=false"));
    }

    @Test
    void genericUploadAndValidationErrors() {
        byte[] generic = OrderCsvParserTest.resource("replay/generic-orders-sample.csv");
        SimulationJob job = replay.submit("generic-orders-sample.csv", generic, new MarginReplayOptions("auto", true, 0.6, null, true));
        assertThat(replay.await(job.id(), Duration.ofSeconds(60))).isTrue();
        SimulationJob done = replay.get(job.id());
        assertThat(done.status()).as(done.error()).isEqualTo(JobStatus.COMPLETED);
        @SuppressWarnings("unchecked") Map<String, Object> file = (Map<String, Object>) done.results().get("file");
        assertThat(file).containsEntry("format", "generic").containsEntry("rows", 50);
        assertThat(done.label()).startsWith("Margin Replay · generic-orders-sample.csv");

        assertThatThrownBy(() -> replay.submit("empty.csv", new byte[0], null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> replay.submit("x.csv", "foo,bar\n1,2\n".getBytes(StandardCharsets.UTF_8), null)).isInstanceOf(ValidationException.class).hasMessageContaining("recognise");
        assertThatThrownBy(() -> replay.submit("x.csv", "order_id,sku,qty\n".getBytes(StandardCharsets.UTF_8), null)).isInstanceOf(ValidationException.class).hasMessageContaining("No orders");
        assertThatThrownBy(() -> replay.submit("big.csv", new byte[MarginReplayService.MAX_BYTES + 1], null)).isInstanceOf(ValidationException.class).hasMessageContaining("10 MB");
    }

    private static double num(Map<String, Object> m, String k) { return ((Number) m.get(k)).doubleValue(); }
}
