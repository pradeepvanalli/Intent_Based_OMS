package ai.intentoms.learning.experiment;

import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.learning.api.ExperimentController;
import ai.intentoms.learning.support.DecisionFabricTestBase;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §12 AC-2 (perfect-order rate as the headline of the lift report, treated versus holdout with a 95% interval) and
 * AC-3 (a 4-week guardrail report, treated versus holdout, with a verdict per metric). Runs in a tenant of its own.
 */
class NorthStarAndGuardrailReportIntegrationTest extends DecisionFabricTestBase {
    @Autowired ExperimentController api;

    @BeforeEach
    void ownTenant() {
        String tenant = "ns_" + Ids.shortId().toLowerCase();
        jdbc.sql("insert into tenant(tenant_id, name) values (:t, :t) on conflict do nothing").param("t", tenant).update();
        TenantContext.set(tenant);
    }

    private void orders(String arm, int n, int perfect, int overridden) {
        Instant at = Instant.now().minusSeconds(3600);
        for (int i = 0; i < n; i++) {
            DecisionRecord r = insertRecord("fulfilment_intent", 1, at.plusSeconds(i), "ship_complete", 0.9, 40, Map.of("experiment", Map.of("arm", arm)), null, "system:test");
            if (i < overridden) records.override(r.id(), "split", "customer asked", "user:ops", at.plusSeconds(i));
            rewardEpisode(r, 0.5, Map.of("otd", 1.0, "perfect", i < perfect ? 1.0 : 0.0));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void perfectOrderRateLeadsTheLiftReportAndTheGuardrailReportJudgesEachMetric() {
        orders("holdout", 40, 20, 1);
        orders("treatment", 40, 32, 12);

        Map<String, Object> headline = (Map<String, Object>) api.lift(90).get("headline");
        assertThat(headline).containsEntry("metric", "perfect_order_rate").containsEntry("betterWhen", "higher")
            .containsEntry("nTreated", 40).containsEntry("nHoldout", 40).containsEntry("treatedMean", 0.8).containsEntry("holdoutMean", 0.5)
            .containsEntry("lift", 0.3).containsEntry("significant", true).containsEntry("improvement", true);
        assertThat((Double) headline.get("ciLower")).isGreaterThan(0.0);

        Map<String, Object> report = api.guardrailReport(4);
        assertThat(report).containsEntry("weeks", 4).containsEntry("comparedWith", "holdout").containsEntry("verdict", "worse");
        List<Map<String, Object>> metrics = (List<Map<String, Object>>) report.get("metrics");
        assertThat(metrics).extracting(m -> m.get("metric"))
            .containsExactly("override_rate", "latency_p95_ms", "contact_rate", "fraud_false_positive_rate", "perfect_order_rate");
        Map<String, Map<String, Object>> by = new java.util.HashMap<>();
        metrics.forEach(m -> by.put((String) m.get("metric"), m));
        assertThat(by.get("override_rate")).containsEntry("verdict", "worse").containsEntry("treated", 0.3).containsEntry("comparison", 0.025);
        assertThat(by.get("latency_p95_ms")).containsEntry("verdict", "not_worse");
        assertThat(by.get("contact_rate")).containsEntry("verdict", "not_worse");
        assertThat(by.get("fraud_false_positive_rate")).containsEntry("verdict", "insufficient");
        assertThat(by.get("perfect_order_rate")).containsEntry("verdict", "not_worse").containsEntry("treated", 0.8).containsEntry("comparison", 0.5);
        assertThat((String) report.get("summary")).contains("Override rate");
        assertThat((List<?>) report.get("weekly")).hasSize(4);
    }
}
