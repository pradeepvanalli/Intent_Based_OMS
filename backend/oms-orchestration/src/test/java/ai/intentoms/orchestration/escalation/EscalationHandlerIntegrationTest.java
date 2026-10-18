package ai.intentoms.orchestration.escalation;

import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.record.DecisionEscalationRepository;
import ai.intentoms.decision.runtime.DecisionService;
import ai.intentoms.decision.runtime.EscalationHandler;
import ai.intentoms.orchestration.OrchestrationTestBase;
import ai.intentoms.workflow.task.HumanTask;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** GR-07 / GR-13: a T3 decision opens a human task; completing it resolves the escalation and records an override. */
class EscalationHandlerIntegrationTest extends OrchestrationTestBase {
    @Autowired DecisionService decisions;
    @Autowired DecisionEscalationRepository escalations;
    @Autowired EscalationHandler handler;
    @Autowired ai.intentoms.platform.config.TenantConfigStore config;

    @Test
    void t3DecisionOpensRiskReviewTaskAndCompletionResolvesIt() {
        assertThat(handler).isInstanceOf(HumanTaskEscalationHandler.class);
        Map<String, Object> input = Map.of(
            "mandate", Map.of("instruction_text", "Buy one grey hoodie size M under 100 dollars", "spend_limit", 100.0, "scope", "apparel", "expires_at", "2026-12-31T00:00:00Z"),
            "order", Map.of("total", 68.0, "items_summary", "1 x HOOD-GRY-M", "ship_to_country", "US"),
            "agent", Map.of("trust_tier", 2));
        DecisionResult r = decisions.decide("agent_mandate_match", input, DecisionOptions.live("order", "ord_test_1", SYSTEM));
        assertThat(r.requiresHuman()).isTrue();
        assertThat(r.escalationId()).isNotNull();
        var esc = escalations.find(r.escalationId()).orElseThrow();
        assertThat(esc.status()).isEqualTo("OPEN");
        assertThat(esc.taskId()).isNotNull();
        HumanTask task = tasks.get(esc.taskId());
        assertThat(task.queue()).isEqualTo(HumanTaskEscalationHandler.QUEUE_RISK);
        assertThat(task.type()).isEqualTo(HumanTaskEscalationHandler.TASK_TYPE);
        assertThat(task.title()).isEqualTo("Decision agent_mandate_match needs a human");
        assertThat(task.payload()).containsEntry("recordId", r.recordId()).containsEntry("escalationId", r.escalationId()).containsKeys("recommendedValue", "confidence", "band", "answers", "reasonCodes", "subjectId");

        tasks.complete(task.id(), "override", "Hoodie is outside the buyer's instruction", Map.of("value", false));
        drainOutbox();
        var resolved = escalations.find(r.escalationId()).orElseThrow();
        assertThat(resolved.status()).isEqualTo("RESOLVED");
        assertThat(resolved.resolvedValue()).isEqualTo(false);
        DecisionRecord rec = decisions.record(r.recordId());
        if (!Boolean.FALSE.equals(rec.value())) {
            assertThat(rec.overriddenAt()).as("human value differs from the recommendation → GR-13 override").isNotNull();
            assertThat(rec.effectiveValue()).isEqualTo(false);
        }
    }

    /** A re-escalation of the same subject links to the open task instead of opening another; completing the task resolves both. */
    @Test
    void reEscalationOfTheSameSubjectLinksToTheOpenTask() {
        config.set("escalation.dedupe_decisions", java.util.List.of("agent_mandate_match"));
        try {
            Map<String, Object> input = Map.of(
                "mandate", Map.of("instruction_text", "Buy one grey hoodie size M under 100 dollars", "spend_limit", 100.0, "scope", "apparel", "expires_at", "2026-12-31T00:00:00Z"),
                "order", Map.of("total", 68.0, "items_summary", "1 x HOOD-GRY-M", "ship_to_country", "US"),
                "agent", Map.of("trust_tier", 2));
            String order = "ord_dedupe_" + System.nanoTime();
            DecisionResult first = decisions.decide("agent_mandate_match", input, DecisionOptions.live("order", order, SYSTEM));
            DecisionResult second = decisions.decide("agent_mandate_match", input, DecisionOptions.live("order", order, SYSTEM));
            var a = escalations.find(first.escalationId()).orElseThrow();
            var b = escalations.find(second.escalationId()).orElseThrow();
            assertThat(a.status()).isEqualTo("OPEN");
            assertThat(b.status()).isEqualTo("LINKED");
            assertThat(b.taskId()).isEqualTo(a.taskId());
            HumanTask task = tasks.get(a.taskId());
            assertThat(task.payload()).containsEntry("latestRecordId", second.recordId()).containsEntry("reEvaluations", 1);

            tasks.complete(task.id(), "approve", "Matches the instruction", Map.of("value", true));
            drainOutbox();
            assertThat(escalations.find(first.escalationId()).orElseThrow().status()).isEqualTo("RESOLVED");
            assertThat(escalations.find(second.escalationId()).orElseThrow().status()).isEqualTo("RESOLVED");
        } finally {
            config.delete("escalation.dedupe_decisions");
        }
    }
}
