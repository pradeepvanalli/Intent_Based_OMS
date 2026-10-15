package ai.intentoms.analytics.kpi;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure KPI maths (FR-ANL-01): empty denominators, medians, baseline targets, deltas, trend shaping and range arithmetic. */
class KpiMathTest {
    private static final LocalDate D0 = LocalDate.of(2026, 10, 1);

    @Test
    void ratesWithZeroDenominators() {
        assertThat(KpiMath.rate(3, 0)).isZero();
        assertThat(KpiMath.rate(3, 4)).isEqualTo(0.75);
        assertThat(KpiMath.rate(0, 0, 1.0)).isEqualTo(1.0);                 // margin-floor compliance with no below-floor orders
        assertThat(KpiMath.value(List.of(new DayPoint(D0, 1, 2), new DayPoint(D0.plusDays(1), 2, 2)))).isEqualTo(0.75);
        assertThat(KpiMath.value(List.of())).isZero();
        assertThat(new DayPoint(D0, 5, 0).hasData()).isFalse();
    }

    @Test
    void median() {
        assertThat(KpiMath.median(List.of())).isEmpty();
        assertThat(KpiMath.median(List.of(7.0))).hasValue(7.0);
        assertThat(KpiMath.median(List.of(9.0, 1.0, 5.0))).hasValue(5.0);
        assertThat(KpiMath.median(List.of(4.0, 1.0, 3.0, 2.0))).hasValue(2.5);
    }

    @Test
    void baselineTargetsAndDeltas() {
        assertThat(KpiMath.targetFromBaseline(null, -0.15)).isNull();
        assertThat(KpiMath.targetFromBaseline(0.20, -0.15)).isEqualTo(0.17);      // split rate -15% vs baseline
        assertThat(KpiMath.targetFromBaseline(60.0, -0.50)).isEqualTo(30.0);      // exception resolution -50%
        assertThat(KpiMath.delta(0.9, null)).isNull();
        assertThat(KpiMath.delta(null, 0.9)).isNull();
        assertThat(KpiMath.delta(0.95, 0.9)).isEqualTo(0.05);
        assertThat(KpiMath.round(Double.NaN, 4)).isZero();
    }

    @Test
    void trendFillsGapsAndBuckets() {
        KpiRange r = new KpiRange(D0, D0.plusDays(4));
        List<Double> t = KpiMath.trend(List.of(new DayPoint(D0.plusDays(1), 1, 2), new DayPoint(D0.plusDays(3), 3, 4)), r, 90, 0.0);
        assertThat(t).containsExactly(0.5, 0.5, 0.5, 0.75, 0.75);                // back-fill then carry forward
        assertThat(KpiMath.trend(List.of(), r, 90, 0.42)).containsExactly(0.42, 0.42, 0.42, 0.42, 0.42);

        KpiRange long_ = new KpiRange(D0, D0.plusDays(179));
        List<DayPoint> daily = new java.util.ArrayList<>();
        for (int i = 0; i < 180; i++) daily.add(new DayPoint(D0.plusDays(i), i % 2, 1));
        List<Double> bucketed = KpiMath.trend(daily, long_, 90, 0.0);
        assertThat(bucketed).hasSize(90).allSatisfy(v -> assertThat(v).isEqualTo(0.5));   // two days per bucket, merged exactly
    }

    @Test
    void mergeAndScale() {
        List<DayPoint> merged = DayPoint.merge(Map.of(D0, 2.0), Map.of(D0, 4.0, D0.plusDays(1), 10.0));
        assertThat(merged).containsExactly(new DayPoint(D0, 2, 4), new DayPoint(D0.plusDays(1), 0, 10));
        assertThat(DayPoint.scaleNumerator(merged, 100).get(0).value()).isEqualTo(50.0);
    }

    @Test
    void rangeArithmetic() {
        KpiRange r = KpiRange.lastDays(D0.plusDays(29), 30);
        assertThat(r.from()).isEqualTo(D0);
        assertThat(r.days()).isEqualTo(30);
        assertThat(r.prior()).isEqualTo(new KpiRange(D0.minusDays(30), D0.minusDays(1)));
        assertThat(r.withPrior().days()).isEqualTo(60);
        assertThat(r.endExclusive()).isEqualTo(D0.plusDays(30).atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
        assertThatThrownBy(() -> new KpiRange(D0, D0.minusDays(1))).isInstanceOf(ai.intentoms.platform.error.ValidationException.class);
    }

    @Test
    void catalogCoversTheUiContract() {
        assertThat(KpiCatalog.ALL).extracting(KpiCatalog.KpiDef::key).containsExactly("touchless_rate", "promise_accuracy", "split_rate", "cost_to_serve", "margin_floor_compliance",
            "store_reject_rate", "exception_resolution_median", "return_abuse_loss", "wismo_per_100", "policy_lead_time", "decision_accuracy", "calibration_error", "override_rate",
            "ai_cost_per_order", "touchless_today", "open_orders", "exceptions_open");
        assertThat(KpiCatalog.ALL).allSatisfy(d -> { assertThat(d.definition()).endsWith("."); assertThat(d.targetDirection()).isIn("gte", "lte"); });
        assertThat(KpiCatalog.rollupKeys()).doesNotContain("touchless_today");
    }
}
