package ai.intentoms.copilot.learning;

import ai.intentoms.copilot.CopilotTestBase;
import ai.intentoms.copilot.support.LlmStubs;
import ai.intentoms.learning.model.Direction;
import ai.intentoms.learning.model.Hypothesis;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.Review;
import ai.intentoms.learning.store.HypothesisRepository;
import ai.intentoms.learning.store.ProposalRepository;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.ids.Ids;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** DF-04 auto-drafting, §6.3 schema hash + validation against the live artifact + author quality, NF-03 monthly budget. */
class LearningPipelineIntegrationTest extends CopilotTestBase {
    @Autowired AuthorScheduler authorScheduler;
    @Autowired LearningLlmAudit audit;
    @Autowired HypothesisRepository hypotheses;
    @Autowired ProposalRepository proposals;
    @Autowired TenantConfigStore config;

    private String overrideHypothesis() {
        Hypothesis h = new Hypothesis(Ids.prefixed("hyp"), Map.of("decision", "return_risk"), "override_rate", Direction.DECREASE,
            Map.of("action", "add_rule", "decision", "return_risk", "groupKey", "return_risk:" + Ids.shortId(), "inputHash", "h", "suggestedThen", "4"),
            0.9, List.of(), "25 overrides agreed on 4 for the same input", Hypothesis.Status.OPEN, Instant.now());
        hypotheses.insert(h);
        return h.hypothesisId();
    }

    private static Map<String, Object> changeset(String expression) {
        return Map.of("targetType", "decision", "targetName", "return_risk", "tier", "T0", "motivatedBy", "People keep sending these returns to inspection",
            "changes", List.of(Map.of("type", "RuleAdd", "argsJson", LlmStubs.json(Map.of("decision", "return_risk", "expression", expression, "thenValue", "4", "reason", "repeat_claims")))));
    }

    @Test
    void authorDraftsValidatedRulesAuditsEveryCallAndStopsAtTheMonthlyBudget() {
        config.delete(LearningLlmAudit.BUDGET_KEY);
        String good = overrideHypothesis();
        LlmStubs.stub(LLM, "policy_changeset", changeset("input.customer.history.not_received_claims >= 5"));

        List<Proposal> drafted = authorScheduler.runOnce();

        Proposal p = drafted.stream().filter(x -> good.equals(x.hypothesisId())).findFirst().orElseThrow();
        assertThat(p.status().name()).isEqualTo("DRAFT");
        assertThat(p.tier().name()).isEqualTo("T2"); // the decision's own tier, not the model's claim of T0
        assertThat(p.changeset().changes()).singleElement().isInstanceOf(PolicyChange.RuleAdd.class);
        assertThat(hypotheses.find(good).orElseThrow().status()).isEqualTo(Hypothesis.Status.DRAFTED);
        Map<String, Object> call = audit.recentCalls(10).stream().filter(c -> p.proposalId().equals(c.get("produced_id"))).findFirst().orElseThrow();
        assertThat(call).containsEntry("role", "author").containsEntry("outcome", "ok").containsEntry("schema_name", "policy_changeset")
            .containsEntry("schema_hash", LearningLlmAudit.schemaHash(LearningSchemas.CHANGESET));
        assertThat(call.get("prompt_hash")).isNotNull();

        // A rule whose CEL does not compile never becomes a proposal.
        LLM.resetAll();
        String bad = overrideHypothesis();
        LlmStubs.stub(LLM, "policy_changeset", changeset("input.customer &&& history"));
        assertThat(authorScheduler.runOnce()).noneMatch(x -> bad.equals(x.hypothesisId()));
        assertThat(audit.recentCalls(10)).anyMatch(c -> bad.equals(c.get("ref_id")) && "invalid".equals(c.get("outcome")));

        // Monthly budget: two stubbed calls already cost more than this, so the next call is skipped without reaching the model.
        config.set(LearningLlmAudit.BUDGET_KEY, 0.001);
        try {
            LLM.resetAll();
            String third = overrideHypothesis();
            LlmStubs.stub(LLM, "policy_changeset", changeset("input.customer.history.not_received_claims >= 6"));
            assertThat(authorScheduler.runOnce()).isEmpty();
            assertThat(LLM.getAllServeEvents()).isEmpty();
            assertThat(audit.recentCalls(10)).anyMatch(c -> third.equals(c.get("ref_id")) && "skipped_budget".equals(c.get("outcome")));
            assertThat(audit.budgetStatus()).containsEntry("exhausted", true);
        } finally {
            config.delete(LearningLlmAudit.BUDGET_KEY);
        }

        // Author quality: the good draft passes critic and replay.
        proposals.setCriticReview(p.proposalId(), new Review(Review.Verdict.LOOKS_SAFE, List.of()));
        proposals.markEvaluated(p.proposalId(), Map.of("liftEstimate", 0.05, "confidenceInterval", Map.of("lower", 0.01, "upper", 0.09)), Instant.now());
        Map<String, Object> quality = audit.authorQuality(30);
        assertThat(((Number) quality.get("drafted")).longValue()).isGreaterThanOrEqualTo(1);
        assertThat(((Number) quality.get("bothPassed")).longValue()).isGreaterThanOrEqualTo(1);
        assertThat(quality.get("qualityRate")).isNotNull();
    }
}
