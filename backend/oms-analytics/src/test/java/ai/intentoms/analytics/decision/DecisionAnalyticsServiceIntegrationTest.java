package ai.intentoms.analytics.decision;

import ai.intentoms.analytics.AnalyticsTestBase;
import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.model.Tier;
import ai.intentoms.decision.runtime.DecisionService;
import ai.intentoms.platform.error.NotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** Per-version decision analytics shape (FR-ANL-02) for the demo {@code fulfilment_intent} decision evaluated with the heuristic provider. */
class DecisionAnalyticsServiceIntegrationTest extends AnalyticsTestBase {
    @Autowired DecisionAnalyticsService analytics;
    @Autowired DecisionService decisions;

    @Test
    void perVersionAnalyticsForFulfilmentIntent() {
        for (int i = 0; i < 4; i++) {
            Map<String, Object> input = Map.of("order", Map.of("channel", "web", "notes", i % 2 == 0 ? "ship together please" : "send as available", "intent_choices", List.of()),
                "customer", Map.of("tier", "silver", "history", Map.of("split_acceptance_rate", 0.5)));
            DecisionResult r = decisions.decide("fulfilment_intent", input, DecisionOptions.live("order", "da_" + i, SYSTEM));
            if (i == 0) decisions.label(r.recordId(), "correct", Map.of("value", true));
            if (i == 1) decisions.override(r.recordId(), "ship_complete", "agent judgement");
        }

        DecisionAnalytics a = analytics.forDecision("fulfilment_intent", 30);
        assertThat(a.name()).isEqualTo("fulfilment_intent");
        assertThat(a.tier()).isEqualTo(Tier.T1);
        assertThat(a.versions()).isNotEmpty();
        DecisionAnalytics.VersionAnalytics v = a.versions().stream().filter(x -> x.version() == 1).findFirst().orElseThrow();
        assertThat(v.status()).isEqualTo("live");
        assertThat(v.volume()).isGreaterThanOrEqualTo(4);
        assertThat(v.bandMix()).containsOnlyKeys("ACT", "CONFIRM", "ESCALATE");
        assertThat(v.bandMix().values().stream().mapToDouble(Double::doubleValue).sum()).isCloseTo(1.0, within(1e-4));
        assertThat(v.layerMix()).containsOnlyKeys("L0_RULE", "L1_SYSTEM_ONE", "L2_LLM", "L3_HUMAN", "DEFAULT");
        assertThat(v.overrideRate()).isBetween(0.0, 1.0);
        assertThat(v.escalationRate()).isBetween(0.0, 1.0);
        assertThat(v.latencyP95Ms()).isGreaterThanOrEqualTo(v.latencyP50Ms());
        assertThat(v.costUsd()).isGreaterThanOrEqualTo(0.0);
        assertThat(v.costPerDecisionUsd()).isGreaterThanOrEqualTo(0.0);
        assertThat(v.accuracy()).isNotNull().isBetween(0.0, 1.0);         // one correct label, one override (wrong)
        assertThat(v.calibration()).hasSize(10);
        assertThat(v.calibration().get(3).bin()).isEqualTo(3);
        assertThat(v.calibration().stream().mapToLong(DecisionAnalytics.CalibrationPoint::count).sum()).isGreaterThanOrEqualTo(2);
        assertThat(v.dailyVolume()).hasSize(30);
        assertThat(v.daily()).hasSize(30);
        assertThat(v.dailyVolume().get(29)).isGreaterThanOrEqualTo(4);   // today is the last point
        assertThat(v.daily().get(29).count()).isEqualTo(v.dailyVolume().get(29));

        List<DecisionAnalytics.OverviewRow> overview = analytics.overview(30);
        DecisionAnalytics.OverviewRow row = overview.stream().filter(o -> o.name().equals("fulfilment_intent")).findFirst().orElseThrow();
        assertThat(row.tier()).isEqualTo(Tier.T1);
        assertThat(row.status()).isEqualTo("live");
        assertThat(row.volume()).isGreaterThanOrEqualTo(4);
        assertThat(overview).extracting(DecisionAnalytics.OverviewRow::name).contains("exception_classify", "return_risk");

        DecisionAnalytics.AiCostReport cost = analytics.aiCost(30);
        assertThat(cost.days()).isEqualTo(30);
        assertThat(cost.totalUsd()).isGreaterThanOrEqualTo(0.0);
        assertThat(cost.byDecision()).allSatisfy(r -> { assertThat(r.decision()).isNotBlank(); assertThat(r.provider()).isNotBlank(); });

        assertThatThrownBy(() -> analytics.forDecision("no_such_decision", 7)).isInstanceOf(NotFoundException.class);
    }
}
