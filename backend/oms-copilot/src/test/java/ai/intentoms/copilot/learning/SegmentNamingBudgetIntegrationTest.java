package ai.intentoms.copilot.learning;

import ai.intentoms.copilot.CopilotTestBase;
import ai.intentoms.copilot.support.LlmStubs;
import ai.intentoms.learning.learner.SegmentNamer;
import ai.intentoms.platform.config.TenantConfigStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** NF-03 / §6.3: analyst segment naming is metered against the monthly learning LLM budget and written to the LLM audit. */
class SegmentNamingBudgetIntegrationTest extends CopilotTestBase {
    @Autowired LlmSegmentNamer namer;
    @Autowired LearningLlmAudit audit;
    @Autowired TenantConfigStore config;

    private static final List<SegmentNamer.Profile> PROFILES = List.of(
        new SegmentNamer.Profile(0, 40, Map.of("split_tolerance", 0.8, "speed_vs_cost", 0.4, "pickup_share", 0.1, "orders", 3.0)),
        new SegmentNamer.Profile(1, 35, Map.of("split_tolerance", 0.2, "speed_vs_cost", -0.5, "pickup_share", 0.6, "orders", 1.5)));
    private static final Map<String, Double> OVERALL = Map.of("split_tolerance", 0.5, "speed_vs_cost", 0.0, "pickup_share", 0.3, "orders", 2.2);

    @Test
    void namingIsAuditedAndStopsWhenTheBudgetIsSpent() {
        config.delete(LearningLlmAudit.BUDGET_KEY);
        LLM.resetAll();
        LlmStubs.stub(LLM, "segment_names", Map.of("segments", List.of(
            Map.of("label", "Happy to split", "description", "Accept several packages and want speed."),
            Map.of("label", "Pickup planners", "description", "Collect in store and watch delivery cost."))));

        var names = namer.name(PROFILES, OVERALL);
        assertThat(names).isPresent();
        assertThat(names.get()).extracting(SegmentNamer.Name::label).containsExactly("Happy to split", "Pickup planners");
        assertThat(audit.recentCalls(10)).anyMatch(c -> "learning_segment_naming".equals(c.get("feature")) && "ok".equals(c.get("outcome"))
            && LearningLlmAudit.schemaHash(LlmSegmentNamer.SCHEMA).equals(c.get("schema_hash")));
        assertThat(audit.monthSpendUsd()).isGreaterThan(0.0); // the call counts toward the learning budget

        config.set(LearningLlmAudit.BUDGET_KEY, 0.0000001);
        try {
            LLM.resetAll();
            assertThat(namer.name(PROFILES, OVERALL)).isEmpty();
            assertThat(LLM.getAllServeEvents()).isEmpty();
            assertThat(audit.recentCalls(10)).anyMatch(c -> "learning_segment_naming".equals(c.get("feature")) && "skipped_budget".equals(c.get("outcome")));
        } finally {
            config.delete(LearningLlmAudit.BUDGET_KEY);
        }
    }
}
