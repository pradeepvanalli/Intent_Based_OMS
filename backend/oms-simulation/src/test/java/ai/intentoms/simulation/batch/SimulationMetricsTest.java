package ai.intentoms.simulation.batch;

import ai.intentoms.simulation.replay.PlanSummary;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-SIM-02 aggregation: rates over zero orders are 0, arithmetic over a mixed population is right. */
class SimulationMetricsTest {
    private static PlanSummary plan(String cost, int packages, Double onTime, Double margin) {
        return new PlanSummary("p", "ship_as_available", packages, List.of("DC-NJ"), new BigDecimal(cost), margin, onTime, null, true, PlanSummary.RECORDED);
    }

    @Test
    void zeroOrdersYieldZeroRatesNotNaN() {
        SimulationMetrics.Snapshot s = new SimulationMetrics().snapshot();
        assertThat(s.orders()).isZero();
        assertThat(s.cost()).isZero();
        assertThat(s.costPerOrder()).isZero();
        assertThat(s.splitRate()).isZero();
        assertThat(s.onTimeRate()).isZero();
        assertThat(s.margin()).isZero();
        assertThat(s.touchlessRate()).isZero();
        assertThat(s.escalations()).isZero();
        Map<String, Object> report = SimulationMetrics.report(s, s, List.of(), 0, 0, 0, false);
        assertThat(report).containsEntry("orders", 0).containsEntry("costPerOrder", 0.0).containsEntry("baselineSplitRate", 0.0).containsEntry("partial", false);
        assertThat(report.get("delta")).isInstanceOf(Map.class);
    }

    @Test
    void aggregatesCostSplitOnTimeMarginTouchlessAndEscalations() {
        SimulationMetrics m = new SimulationMetrics();
        m.add(plan("10.00", 1, 0.9, 0.40), 2, true, 0);      // touchless
        m.add(plan("30.00", 2, 0.7, 0.20), 2, false, 1);     // split, escalated
        m.add(null, 1, true, 0);                             // unsourced but touchless
        SimulationMetrics.Snapshot s = m.snapshot();
        assertThat(s.orders()).isEqualTo(3);
        assertThat(s.sourcedOrders()).isEqualTo(2);
        assertThat(s.unsourcedOrders()).isEqualTo(1);
        assertThat(s.cost()).isEqualTo(40.0);
        assertThat(s.costPerOrder()).isCloseTo(13.333333, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(s.packages()).isEqualTo(3);
        assertThat(s.splitRate()).isEqualTo(0.5);
        assertThat(s.onTimeRate()).isEqualTo(0.8);
        assertThat(s.margin()).isCloseTo(0.3, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(s.touchlessRate()).isCloseTo(0.666667, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(s.escalations()).isEqualTo(1);
        assertThat(s.decisions()).isEqualTo(5);
    }

    @Test
    void deltaIsSimulatedMinusBaselineAndReportCarriesUiFields() {
        SimulationMetrics base = new SimulationMetrics(), sim = new SimulationMetrics();
        base.add(plan("10.00", 1, 0.9, 0.4), 1, true, 0);
        sim.add(plan("14.00", 2, 0.8, 0.3), 1, false, 1);
        Map<String, Object> delta = SimulationMetrics.Snapshot.delta(base.snapshot(), sim.snapshot());
        assertThat(delta).containsEntry("cost", 4.0).containsEntry("packages", 1).containsEntry("splitRate", 1.0).containsEntry("escalations", 1).containsEntry("touchlessRate", -1.0);
        assertThat((Double) delta.get("onTimeRate")).isCloseTo(-0.1, org.assertj.core.data.Offset.offset(1e-9));
        Map<String, Object> report = SimulationMetrics.report(base.snapshot(), sim.snapshot(), List.of(Map.of("orderId", "o1")), 1, 1, 0, true);
        assertThat(report).containsEntry("orders", 1).containsEntry("costPerOrder", 14.0).containsEntry("baselineCostPerOrder", 10.0).containsEntry("escalations", 1)
            .containsEntry("baselineEscalations", 0).containsEntry("flippedDecisions", 1).containsEntry("partial", true);
        assertThat((List<?>) report.get("perOrderSample")).hasSize(1);
    }
}
