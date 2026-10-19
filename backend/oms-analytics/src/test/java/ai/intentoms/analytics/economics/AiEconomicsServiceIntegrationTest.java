package ai.intentoms.analytics.economics;

import ai.intentoms.analytics.AnalyticsTestBase;
import ai.intentoms.analytics.kpi.KpiRange;
import ai.intentoms.order.model.Order;
import ai.intentoms.platform.ids.Ids;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * AI economics over a three-day window owned by this test (days 120..118 ago, so other tests' "today" records never leak in):
 * layer mix, automation, human decisions avoided (T1/T2 only), overrides, escalations, cost per 1k / per order, hours saved,
 * the per-decision table, and the empty-range shape.
 */
class AiEconomicsServiceIntegrationTest extends AnalyticsTestBase {
    @Autowired AiEconomicsService economics;
    @Autowired ai.intentoms.decision.record.DecisionRecordRepository decisionRecords;

    @Test
    void reportOverOwnedWindow() {
        LocalDate d0 = KpiRange.dayOf(time.now()).minusDays(120), d1 = d0.plusDays(1), d2 = d0.plusDays(2);
        KpiRange range = new KpiRange(d0, d2);
        // fulfilment_intent is T1 in the demo config; exception_classify is T0.
        String r1 = record(d0, "fulfilment_intent", "L1_SYSTEM_ONE", "ACT", "act", 0.92, 300, 0.00002, false);
        record(d0, "fulfilment_intent", "L1_SYSTEM_ONE", "ACT", "act", 0.88, 500, 0.00002, false);
        record(d1, "fulfilment_intent", "L1_SYSTEM_ONE", "ACT", "act", 0.95, 400, 0.00002, false);
        record(d1, "fulfilment_intent", "L0_RULE", "ACT", "act", 1.0, 5, 0, false);
        record(d2, "fulfilment_intent", "L3_HUMAN", "ESCALATE", "escalate", 0.4, 50, 0, true);
        record(d2, "exception_classify", "L1_SYSTEM_ONE", "ACT", "act", 0.7, 200, 0.00001, false);
        // decision_record is append-only (V405): an override is an annotation through the repository.
        ai.intentoms.platform.tenant.TenantContext.runAs(TENANT, () -> decisionRecords.override(r1, "ship_complete", "judgement", "ops", java.time.Instant.now()));
        Order o = newOrder("released", List.of(group("x", 0, "DC-NJ", "8.00", d1.plusDays(3))), null);
        placedOn(o.id(), d1);

        AiEconomics a = economics.report(range);
        assertThat(a.range().from()).isEqualTo(d0);
        assertThat(a.range().days()).isEqualTo(3);
        assertThat(a.totalDecisions()).isEqualTo(6);
        assertThat(a.actingDecisions()).isEqualTo(5);
        assertThat(a.automationRate()).isCloseTo(5 / 6.0, within(1e-4));
        assertThat(a.humanDecisionsAvoided()).isEqualTo(4);                 // the T0 exception_classify act does not count
        assertThat(a.overrides()).isEqualTo(1);
        assertThat(a.overrideRate()).isCloseTo(0.2, within(1e-6));          // 1 override / 5 acting
        assertThat(a.escalations()).isEqualTo(1);
        assertThat(a.escalationRate()).isCloseTo(1 / 6.0, within(1e-4));
        assertThat(a.totalAiCostUsd()).isCloseTo(0.00007, within(1e-9));
        assertThat(a.costPer1kDecisionsUsd()).isCloseTo(0.00007 / 6 * 1000, within(1e-6));
        assertThat(a.orders()).isEqualTo(1);
        assertThat(a.costPerOrderUsd()).isCloseTo(0.00007, within(1e-9));
        assertThat(a.minutesPerHumanDecision()).isEqualTo(2.0);
        assertThat(a.estimatedAnalystHoursSaved()).isCloseTo(4 * 2 / 60.0, within(1e-3));

        assertThat(a.byLayer()).extracting(AiEconomics.LayerStats::layer).containsExactly("L0_RULE", "L1_SYSTEM_ONE", "L2_LLM", "L3_HUMAN", "DEFAULT");
        AiEconomics.LayerStats l1 = a.byLayer().get(1);
        assertThat(l1.count()).isEqualTo(4);
        assertThat(l1.share()).isCloseTo(4 / 6.0, within(1e-4));
        assertThat(l1.latencyP50Ms()).isBetween(200.0, 500.0);
        assertThat(l1.latencyP95Ms()).isGreaterThanOrEqualTo(l1.latencyP50Ms());
        assertThat(a.byLayer().get(2).count()).isZero();                      // L2_LLM unused

        assertThat(a.daily()).hasSize(3);
        assertThat(a.daily().get(0).date()).isEqualTo(d0);
        assertThat(a.daily().get(0).total()).isEqualTo(2);
        assertThat(a.daily().get(1).byLayer()).containsEntry("L0_RULE", 1L).containsEntry("L1_SYSTEM_ONE", 1L).containsKey("DEFAULT");
        assertThat(a.daily().get(2).byLayer()).containsEntry("L3_HUMAN", 1L);

        assertThat(a.decisions()).extracting(AiEconomics.DecisionRow::name).containsExactly("fulfilment_intent", "exception_classify");
        AiEconomics.DecisionRow fi = a.decisions().get(0);
        assertThat(fi.tier()).isEqualTo("T1");
        assertThat(fi.volume()).isEqualTo(5);
        assertThat(fi.automationRate()).isCloseTo(0.8, within(1e-6));
        assertThat(fi.avgConfidence()).isCloseTo((0.92 + 0.88 + 0.95 + 1.0 + 0.4) / 5, within(1e-3));
        assertThat(fi.costPer1kUsd()).isCloseTo(0.00006 / 5 * 1000, within(1e-6));
        assertThat(fi.latencyP95Ms()).isBetween(400.0, 500.0);
        assertThat(fi.overrideRate()).isCloseTo(0.25, within(1e-6));        // 1 override / 4 acting
        assertThat(a.decisions().get(1).tier()).isEqualTo("T0");
        assertThat(a.assumptions()).anySatisfy(s -> assertThat(s).contains("2 min"));

        // tenant-configurable minutes per human decision
        config.set(AiEconomicsService.MINUTES_PER_HUMAN_DECISION, 6);
        try {
            AiEconomics b = economics.report(range);
            assertThat(b.minutesPerHumanDecision()).isEqualTo(6.0);
            assertThat(b.estimatedAnalystHoursSaved()).isCloseTo(0.4, within(1e-3));
        } finally {
            config.set(AiEconomicsService.MINUTES_PER_HUMAN_DECISION, 2);
        }
    }

    @Test
    void emptyRangeRendersZeros() {
        LocalDate d = KpiRange.dayOf(time.now()).minusDays(400);
        AiEconomics a = economics.report(new KpiRange(d, d.plusDays(6)));
        assertThat(a.totalDecisions()).isZero();
        assertThat(a.automationRate()).isZero();
        assertThat(a.overrideRate()).isZero();
        assertThat(a.costPer1kDecisionsUsd()).isZero();
        assertThat(a.costPerOrderUsd()).isZero();
        assertThat(a.estimatedAnalystHoursSaved()).isZero();
        assertThat(a.byLayer()).hasSize(5).allSatisfy(l -> { assertThat(l.count()).isZero(); assertThat(l.share()).isZero(); });
        assertThat(a.daily()).hasSize(7).allSatisfy(x -> { assertThat(x.total()).isZero(); assertThat(x.byLayer()).hasSize(5); });
        assertThat(a.decisions()).isEmpty();
        assertThat(a.assumptions()).isNotEmpty();
    }

    private String record(LocalDate day, String decision, String layer, String band, String action, double confidence, long latencyMs, double costUsd, boolean requiresHuman) {
        String id = "dr_" + Ids.shortId();
        Timestamp at = Timestamp.from(day.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(7200));
        jdbc.sql("insert into decision_record (id, tenant_id, decision, version, mode, subject_type, subject_id, layer, band, action, value, confidence, input_hash, latency_ms, cost_usd, requires_human, created_at) "
                + "values (:id, :t, :d, coalesce((select max(version) from decision_definition where tenant_id = :t and name = :d and status = 'live'), 1), "
                + "'LIVE', 'order', :s, :layer, :band, :action, '\"x\"'::jsonb, :conf, :h, :lat, :cost, :rh, :at)")
            .params(Map.ofEntries(Map.entry("id", id), Map.entry("t", TENANT), Map.entry("d", decision), Map.entry("s", "O" + id), Map.entry("layer", layer), Map.entry("band", band), Map.entry("action", action),
                Map.entry("conf", confidence), Map.entry("h", "h_" + id), Map.entry("lat", latencyMs), Map.entry("cost", costUsd), Map.entry("rh", requiresHuman), Map.entry("at", at)))
            .update();
        return id;
    }
}
