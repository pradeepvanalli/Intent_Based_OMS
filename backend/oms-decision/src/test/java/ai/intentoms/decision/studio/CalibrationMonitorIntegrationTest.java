package ai.intentoms.decision.studio;

import ai.intentoms.decision.DecisionTestBase;
import ai.intentoms.decision.analytics.DecisionAnalyticsRepository;
import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.model.DriftReport;
import ai.intentoms.decision.runtime.DecisionService;
import ai.intentoms.platform.ledger.Actor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CalibrationMonitorIntegrationTest extends DecisionTestBase {
    @Autowired CalibrationMonitor monitor;
    @Autowired DecisionService decisions;
    @Autowired DecisionAnalyticsRepository analytics;

    @Test
    void miscalibratedLabelsRaiseDrift() {
        for (int i = 0; i < 12; i++) {
            DecisionResult r = decisions.decide("customer_request_intent", Map.of("request", Map.of("channel", "chat", "text", "where is my order, tracking status please " + i), "order", Map.of("status", "shipped", "nonce", System.nanoTime())),
                DecisionOptions.live("order", "cal_" + i, Actor.system("test")));
            assertThat(r.value()).isEqualTo("wismo");
            decisions.label(r.recordId(), "correct", Map.of("value", false));             // every confident-ish answer judged wrong
        }
        DriftReport report = monitor.check("customer_request_intent", 1);
        assertThat(report.labelled()).isGreaterThanOrEqualTo(12);
        assertThat(report.calibrationError()).isGreaterThan(report.maxCalibrationError());
        assertThat(report.drift()).isTrue();
        assertThat(events("config", "decision:customer_request_intent", "decision.drift_detected")).isNotEmpty();

        var stats = analytics.perVersion("customer_request_intent");
        assertThat(stats).isNotEmpty();
        assertThat(stats.get(0).volume()).isGreaterThanOrEqualTo(12);
        assertThat(stats.get(0).calibration()).hasSize(10);
        assertThat(stats.get(0).latencyP95Ms()).isGreaterThanOrEqualTo(stats.get(0).latencyP50Ms());
        assertThat(analytics.daily("customer_request_intent", 7)).isNotEmpty();
        assertThat(monitor.run()).anyMatch(d -> d.decision().equals("customer_request_intent"));
    }
}
