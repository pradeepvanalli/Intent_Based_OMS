package ai.intentoms.copilot.learning;

import ai.intentoms.copilot.NoLlmTestBase;
import ai.intentoms.decision.definition.DecisionDefinitionLoader;
import ai.intentoms.decision.studio.PromptVersionService;
import ai.intentoms.learning.model.Direction;
import ai.intentoms.learning.model.Hypothesis;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.Review;
import ai.intentoms.learning.store.HypothesisRepository;
import ai.intentoms.learning.store.ProposalRepository;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Without a model key the learning loop still drafts and reviews: the template author writes the rule people agreed
 * on (DF-04, plus a draft prompt version, DF-06), an abuse rule for an address-reuse ring (FD-04), a rule retirement
 * and an allow-list change, each labelled as template output and validated against the live decision; the rule-based
 * critic reviews it with source "rules".
 */
class TemplateAuthorCriticIntegrationTest extends NoLlmTestBase {
    @Autowired AuthorService author;
    @Autowired AuthorScheduler scheduler;
    @Autowired CriticService critic;
    @Autowired HypothesisRepository hypotheses;
    @Autowired ProposalRepository proposals;
    @Autowired PromptVersionService prompts;
    @Autowired DecisionDefinitionLoader loader;

    String tenant;

    @BeforeEach
    void ownTenant() {
        tenant = "tpl-" + Ids.shortId();
        jdbc.sql("insert into tenant(tenant_id, name) values (:t, :t) on conflict do nothing").param("t", tenant).update();
        TenantContext.set(tenant);
        loader.loadDirectory(DEMO.resolve("decisions"));
    }

    @AfterEach
    void back() { TenantContext.set(TENANT); }

    private Hypothesis open(String decision, Map<String, Object> scope, Map<String, Object> lever, String rationale) {
        Hypothesis h = new Hypothesis(Ids.prefixed("hyp"), scope, "override_rate", Direction.DECREASE, lever, 0.9, List.of(), rationale, Hypothesis.Status.OPEN, Instant.now());
        hypotheses.insert(h);
        return h;
    }

    @Test
    void templateAuthorDraftsWhatHypothesesStatePreciselyAndTheRuleCriticReviewsIt() {
        // DF-04: the override pattern becomes the rule, and a draft prompt version states the guidance (DF-06).
        Hypothesis override = open("fulfilment_intent", Map.of("decision", "fulfilment_intent"), Map.of("action", "add_rule", "decision", "fulfilment_intent",
            "groupKey", "fulfilment_intent:x", "suggestedThen", "ship_complete", "when", "input.order.channel == 'marketplace'", "resolutions", 24, "agreement", 0.96),
            "24 resolutions agreed on ship_complete for marketplace orders");
        Proposal p = scheduler.runOnce().stream().filter(x -> override.hypothesisId().equals(x.hypothesisId())).findFirst().orElseThrow();
        assertThat(p.createdBy()).isEqualTo(TemplateAuthor.ACTOR);
        assertThat(p.changeset().motivatedBy()).startsWith("Template author (no LLM)");
        assertThat(p.changeset().changes()).singleElement().isInstanceOfSatisfying(PolicyChange.RuleAdd.class, ra -> {
            assertThat(ra.expression()).isEqualTo("input.order.channel == 'marketplace'");
            assertThat(ra.thenValue()).isEqualTo("ship_complete");
        });
        assertThat(hypotheses.find(override.hypothesisId()).orElseThrow().status()).isEqualTo(Hypothesis.Status.DRAFTED);
        assertThat(prompts.list("fulfilment_intent")).anySatisfy(v -> {
            assertThat(v.status()).isEqualTo("draft");
            assertThat(v.text()).contains("input.order.channel == 'marketplace'").contains("ship_complete");
        });

        // FD-04: an address-reuse ring becomes a hold rule on payment_risk.
        Hypothesis ring = open("payment_risk", Map.of("decision", "payment_risk", "addressToken", "tok_ring1", "pattern", "address_reuse_ring"),
            Map.of("decision", "payment_risk", "lever", "ruleAdd", "addressToken", "tok_ring1"), "4 customers share one address");
        PolicyChange.RuleAdd ringRule = (PolicyChange.RuleAdd) author.draft(ring.hypothesisId()).orElseThrow().changeset().changes().get(0);
        assertThat(ringRule.expression()).contains("input.address.token == 'tok_ring1'");
        assertThat(ringRule.thenValue()).isEqualTo("true");

        // Retiring a rule that exists works; one that does not (a hallucinated lever) is refused.
        Hypothesis retire = open("payment_risk", Map.of("decision", "payment_risk"), Map.of("action", "retire_rule", "decision", "payment_risk", "ruleId", "no_risk_signals"), "never fires");
        assertThat(author.draft(retire.hypothesisId()).orElseThrow().changeset().changes()).singleElement().isInstanceOf(PolicyChange.RuleRetire.class);
        Hypothesis ghost = open("payment_risk", Map.of("decision", "payment_risk"), Map.of("action", "retire_rule", "decision", "payment_risk", "ruleId", "no_such_rule"), "x");
        assertThat(author.draft(ghost.hypothesisId())).isEmpty();

        // Allow-list changes are config changes; refund never joins an autonomous list.
        Hypothesis allow = open("autonomy", Map.of(), Map.of("lever", "allowListChange", "listName", "autonomy.allowed_actions", "entry", "re_promise", "add", false),
            "re_promise actions were rolled back 6 times");
        Proposal allowP = author.draft(allow.hypothesisId()).orElseThrow();
        assertThat(allowP.changeset().target().toString()).isEqualTo("config:autonomy.allowed_actions");
        assertThat(allowP.tier().name()).isEqualTo("T2");
        Hypothesis refund = open("autonomy", Map.of(), Map.of("lever", "allowListChange", "listName", "autonomy.allowed_actions", "entry", "refund", "add", true), "x");
        assertThat(author.draft(refund.hypothesisId())).isEmpty();

        // The rule-based critic reviews an evaluated proposal without a model.
        proposals.markEvaluated(p.proposalId(), Map.of("method", "replay", "liftEstimate", 0.04, "sampleSize", 12, "confidenceInterval", Map.of("lower", -0.01, "upper", 0.09)), Instant.now());
        // XP-02: the evaluation alone brings a review; nobody had to ask the critic.
        Review automatic = proposals.find(p.proposalId()).orElseThrow().criticReview();
        assertThat(automatic).isNotNull();
        assertThat(automatic.source()).isEqualTo(Review.RULES);
        assertThat(automatic.verdict()).isEqualTo(Review.Verdict.NEEDS_CHANGES);
        assertThat(automatic.reviewedAt()).isNotNull();
        Review review = critic.review(p.proposalId()).orElseThrow();
        assertThat(review.source()).isEqualTo(Review.RULES);
        assertThat(review.verdict()).isEqualTo(Review.Verdict.NEEDS_CHANGES);
        assertThat(review.risks()).anyMatch(r -> r.contains("includes no effect")).anyMatch(r -> r.contains("Only 12 cases"));
        assertThat(proposals.find(p.proposalId()).orElseThrow().criticReview().source()).isEqualTo(Review.RULES);

        proposals.markEvaluated(allowP.proposalId(), Map.of("method", "replay", "liftEstimate", -0.02, "sampleSize", 80, "confidenceInterval", Map.of("lower", -0.05, "upper", 0.01)), Instant.now());
        assertThat(critic.review(allowP.proposalId()).orElseThrow().verdict()).isEqualTo(Review.Verdict.RISKY);
    }
}
