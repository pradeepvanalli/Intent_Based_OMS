package ai.intentoms.analytics.kpi;

import ai.intentoms.analytics.AnalyticsTestBase;
import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.runtime.DecisionService;
import ai.intentoms.monitoring.exception.ExceptionRecord;
import ai.intentoms.monitoring.exception.ExceptionRequest;
import ai.intentoms.monitoring.exception.ExceptionService;
import ai.intentoms.order.model.Order;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.ledger.NewEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Section 13 dashboard end to end (FR-ANL-01): seeded orders, ledger events, decisions and exceptions produce every KPI key with sane values. */
class KpiServiceIntegrationTest extends AnalyticsTestBase {
    @Autowired KpiService kpis;
    @Autowired KpiDailyRollup rollup;
    @Autowired DecisionService decisions;
    @Autowired ExceptionService exceptions;

    private static Map<String, Object> intentInput(String notes) {
        return Map.of("order", Map.of("channel", "web", "notes", notes, "intent_choices", List.of()), "customer", Map.of("tier", "gold", "history", Map.of("split_acceptance_rate", 0.4)));
    }

    @Test
    void dashboardReflectsSeededData() {
        LocalDate today = KpiRange.dayOf(time.now());
        // A: delivered on time, decision labelled correct. B: delivered late, two groups, decision overridden (not touchless).
        // C: delivered on time, decision labelled wrong. D: cancelled. E: open, below the margin floor with an approval, two exceptions.
        Order a = newOrder("delivered", List.of(group("a", 0, "DC-EAST", "8.00", today.plusDays(3))), null);
        Order b = newOrder("delivered", List.of(group("b", 0, "DC-EAST", "5.00", today.minusDays(1)), group("b", 1, "DC-WEST", "6.00", today.minusDays(1))), null);
        Order c = newOrder("delivered", List.of(group("c", 0, "DC-EAST", "10.00", today)), null);
        Order d = newOrder("cancelled", List.of(group("d", 0, "DC-EAST", "4.00", today.plusDays(2))), null);
        Order e = newOrder("released", List.of(group("e", 0, "DC-EAST", "7.00", today.plusDays(2))), belowFloor("task_1"));
        delivered(a.id()); delivered(b.id()); delivered(c.id());
        events.append("order", a.id(), new NewEvent("cs.wismo", Map.of("channel", "chat"), SYSTEM));

        DecisionResult ra = decisions.decide("fulfilment_intent", intentInput("please ship everything together"), DecisionOptions.live("order", a.id(), SYSTEM));
        DecisionResult rb = decisions.decide("fulfilment_intent", intentInput("send what you have"), DecisionOptions.live("order", b.id(), SYSTEM));
        DecisionResult rc = decisions.decide("fulfilment_intent", intentInput("gift, no rush"), DecisionOptions.live("order", c.id(), SYSTEM));
        decisions.label(ra.recordId(), "correct", Map.of("value", true));
        decisions.label(rc.recordId(), "correct", Map.of("value", false));
        decisions.override(rb.recordId(), "ship_complete", "customer called");

        ExceptionRecord x1 = exceptions.raise(new ExceptionRequest("carrier", "address_invalid", "Address undeliverable: invalid zip", e.id(), "L1", null, Map.of()), "address");
        ExceptionRecord x2 = exceptions.raise(new ExceptionRequest("cs", "address_unit_missing", "Customer reports apartment number missing", e.id(), "L1", null, Map.of("unit", true)), "address");
        assertThat(x2.id()).isNotEqualTo(x1.id());                                      // a different code is a new exception, not a recurrence
        jdbc.sql("update exception set created_at = :c where id = :id").param("c", Timestamp.from(time.now().minus(Duration.ofMinutes(30)))).param("id", x1.id()).update();
        exceptions.resolve(x1.id(), "address_corrected", "fixed with customer");

        // Policy change lead time: draft and publish of a T0 version on the config aggregate (DecisionStudioService shape).
        events.append("config", "decision:kpi_probe", new NewEvent("decision.definition.drafted", Map.of("decision", "kpi_probe", "version", 2, "tier", "T0", "reason", "draft created"), SYSTEM));
        events.append("config", "decision:kpi_probe", new NewEvent("decision.definition.published", Map.of("decision", "kpi_probe", "version", 2, "reason", "live"), SYSTEM));

        kpis.invalidate();
        KpiRange range = KpiRange.lastDays(today, 30);
        KpiDashboard dash = kpis.dashboard(range);

        assertThat(dash.range().from()).isEqualTo(range.from().toString());
        assertThat(dash.range().to()).isEqualTo(today.toString());
        assertThat(dash.kpis()).extracting(Kpi::key).containsExactlyElementsOf(KpiCatalog.ALL.stream().map(KpiCatalog.KpiDef::key).toList());
        assertThat(dash.kpis()).allSatisfy(k -> {
            assertThat(k.definition()).isNotBlank();
            assertThat(k.label()).isNotBlank();
            assertThat(k.targetDirection()).isIn("gte", "lte");
            assertThat(k.trend()).isNotEmpty().doesNotContainNull();
            assertThat(k.value()).isGreaterThanOrEqualTo(0.0);
        });

        assertThat(dash.kpi("touchless_rate").value()).isEqualTo(0.75);                   // A, C, D touchless; B overridden
        assertThat(dash.kpi("touchless_rate").target()).isEqualTo(0.9);
        assertThat(dash.kpi("touchless_rate").trend()).hasSize(30);
        assertThat(dash.kpi("touchless_today").value()).isEqualTo(0.75);
        assertThat(dash.kpi("touchless_today").trend()).hasSize(7);
        assertThat(dash.kpi("promise_accuracy").value()).isCloseTo(2.0 / 3.0, within(1e-6));
        assertThat(dash.kpi("split_rate").value()).isEqualTo(0.2);                        // B of five orders
        assertThat(dash.kpi("split_rate").target()).isNull();                             // no baseline recorded yet
        assertThat(dash.kpi("cost_to_serve").value()).isEqualTo(8.0);                     // (8 + 11 + 10 + 4 + 7) / 5
        assertThat(dash.kpi("margin_floor_compliance").value()).isEqualTo(1.0);
        assertThat(dash.kpi("store_reject_rate").value()).isZero();
        assertThat(dash.kpi("exception_resolution_median").value()).isCloseTo(30.0, within(1.0));
        assertThat(dash.kpi("exception_resolution_median").unit()).isEqualTo("minutes");
        assertThat(dash.kpi("return_abuse_loss").value()).isZero();
        assertThat(dash.kpi("wismo_per_100").value()).isEqualTo(20.0);                    // one contact per five orders
        assertThat(dash.kpi("policy_lead_time").value()).isBetween(0.0, 1.0);             // drafted and published seconds apart
        assertThat(dash.kpi("policy_lead_time").target()).isEqualTo(24.0);
        assertThat(dash.kpi("decision_accuracy").value()).isCloseTo(1.0 / 3.0, within(1e-6));   // A correct, C wrong, B overridden
        assertThat(dash.kpi("calibration_error").value()).isBetween(0.0, 1.0);
        assertThat(dash.kpi("calibration_error").target()).isEqualTo(0.05);
        assertThat(dash.kpi("override_rate").value()).isBetween(0.0, 1.0);
        assertThat(dash.kpi("override_rate").target()).isEqualTo(0.05);
        assertThat(dash.kpi("ai_cost_per_order").target()).isNull();
        assertThat(dash.kpi("open_orders").value()).isGreaterThanOrEqualTo(1);
        assertThat(dash.kpi("exceptions_open").value()).isGreaterThanOrEqualTo(1);

        // Cache: same instance within the TTL, recomputed after invalidation; baseline config turns the relative target on.
        assertThat(kpis.dashboard(range)).isSameAs(dash);
        config.set(KpiService.CONFIG_BASELINE_PREFIX + "split_rate", 0.2);
        config.set(ai.intentoms.platform.config.ConfigKeys.AI_BUDGET_USD_PER_DAY, 5.0);
        kpis.invalidate();
        KpiDashboard again = kpis.dashboard(range);
        assertThat(again).isNotSameAs(dash);
        assertThat(again.kpi("split_rate").target()).isEqualTo(0.17);
        assertThat(again.kpi("ai_cost_per_order").target()).isEqualTo(5.0 * 30 / 5);
        config.delete(KpiService.CONFIG_BASELINE_PREFIX + "split_rate");
        config.delete(ai.intentoms.platform.config.ConfigKeys.AI_BUDGET_USD_PER_DAY);
    }

    @Test
    void marginFloorComplianceIsFullWhenNothingWasBelowTheFloor() {
        LocalDate day = KpiRange.dayOf(time.now()).minusDays(45);        // outside the live window: only kpi_daily can see it
        Order o = newOrder("delivered", List.of(group("x", 0, "DC-EAST", "3.50", day)), null);
        placedOn(o.id(), day);
        KpiRange range = KpiRange.single(day);

        kpis.invalidate();
        KpiDashboard before = kpis.dashboard(range);
        assertThat(before.kpi("split_rate").value()).isZero();
        assertThat(before.kpi("margin_floor_compliance").value()).isEqualTo(1.0);

        int written = rollup.rollup(day);
        assertThat(written).isGreaterThan(0);
        List<String> keys = jdbc.sql("select key from kpi_daily where tenant_id = :t and day = :d order by key").param("t", TENANT).param("d", day).query(String.class).list();
        assertThat(keys).contains("touchless_rate", "split_rate", "cost_to_serve", "open_orders", "exceptions_open").doesNotContain("touchless_today");

        KpiDashboard after = kpis.dashboard(range);
        assertThat(after.kpi("touchless_rate").value()).isEqualTo(1.0);
        assertThat(after.kpi("cost_to_serve").value()).isEqualTo(3.5);
        assertThat(after.kpi("margin_floor_compliance").value()).isEqualTo(1.0);
        assertThat(after.kpi("open_orders").trend()).hasSize(1);
        assertThat(Json.write(after)).contains("\"targetDirection\"");
    }
}
