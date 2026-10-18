package ai.intentoms.decision.studio;

import ai.intentoms.decision.DecisionTestBase;
import ai.intentoms.decision.TestYaml;
import ai.intentoms.decision.definition.DecisionDefinitionRepository;
import ai.intentoms.decision.model.DecisionDefinition;
import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.model.EvaluationReport;
import ai.intentoms.decision.model.ImpactReport;
import ai.intentoms.decision.model.Layer;
import ai.intentoms.decision.model.LintReport;
import ai.intentoms.decision.runtime.DecisionService;
import ai.intentoms.platform.config.ConfigKeys;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.error.ForbiddenException;
import ai.intentoms.platform.error.GuardrailViolationException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ledger.Actor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecisionStudioIntegrationTest extends DecisionTestBase {
    private static final Set<String> EDITOR = Set.of("decision.read", "decision.edit");
    private static final Set<String> APPROVER = Set.of("decision.read", "decision.approve", "decision.publish");

    @Autowired DecisionStudioService studio;
    @Autowired DecisionService decisions;
    @Autowired DecisionDefinitionRepository definitions;
    @Autowired TenantConfigStore config;

    @AfterEach
    void restoreSeededLiveVersion() {
        // Leave the catalogue as the seeder created it so other test classes see the seeded (file) version live.
        for (String name : new String[]{"fulfilment_intent", "return_risk", "injection_screen", "backorder_next_action"}) {
            int seeded = new ai.intentoms.decision.definition.DecisionDefinitionParser().parse(TestYaml.demo(name)).version();
            for (var row : definitions.versions(name)) {
                if (row.definition().version() == seeded) definitions.setStatus(name, seeded, "live", 0);
                else if (!row.definition().status().equals("retired") && !row.definition().status().equals("draft")) definitions.setStatus(name, row.definition().version(), "retired", null);
            }
        }
        config.delete(ConfigKeys.DECISION_KILL_SWITCH_PREFIX + "backorder_next_action");
    }

    @Test
    void fullLifecycleDraftToLiveAndRollback() {
        // Live traffic so the impact diff has something to replay.
        for (String n : new String[]{"ship everything together please", "each item as soon as it is ready", "collect in store", "no particular preference"}) {
            decisions.decide("fulfilment_intent", intentInput(n, "lc_" + n.hashCode()), DecisionOptions.live("order", "lc_" + n.hashCode(), Actor.system("test")));
        }

        actAs("alice", EDITOR);
        // Draft swaps the descriptions of two options so that recorded inputs flip.
        String yaml = TestYaml.demo("fulfilment_intent")
            .replace("ship_complete: Everything together in one delivery, even if it arrives later", "ship_complete: Each item as soon as it is ready, in separate shipments")
            .replace("ship_as_available: Each item as soon as it is ready, in separate shipments", "ship_as_available: Everything together in one delivery, even if it arrives later");
        DecisionDefinition draft = studio.createDraft("fulfilment_intent", yaml);
        int v = draft.version();
        int seeded = new ai.intentoms.decision.definition.DecisionDefinitionParser().parse(TestYaml.demo("fulfilment_intent")).version();
        assertThat(v).isGreaterThan(seeded);
        assertThat(draft.status()).isEqualTo("draft");

        LintReport lint = studio.lint("fulfilment_intent", v);
        assertThat(lint.ok()).isTrue();

        DecisionDefinition edited = studio.edit("fulfilment_intent", v, Map.of("bands.act", 0.85, "questions.intent.criteria.unclear", "Cannot tell from the order"));
        assertThat(edited.bands().actGte()).isEqualTo(0.85);
        assertThat(edited.questions().get("intent").choiceCriteria().get("unclear")).isEqualTo("Cannot tell from the order");
        assertThatThrownBy(() -> studio.edit("fulfilment_intent", v, Map.of("version", 99))).isInstanceOf(ValidationException.class);

        ImpactReport impact = studio.impactDiff("fulfilment_intent", v, 7, 500);
        assertThat(impact.sampled()).isGreaterThanOrEqualTo(4);
        assertThat(impact.flips()).isGreaterThanOrEqualTo(2);
        assertThat(impact.flipRate()).isGreaterThan(0);
        assertThat(impact.sampleFlips()).isNotEmpty();
        assertThat(impact.textChanges()).isNotEmpty();
        assertThat(impact.bandMixBefore().values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(impact.sampled());

        // Publishing before approval or evaluation is blocked (GR-10, GR-11).
        assertThatThrownBy(() -> studio.publish("fulfilment_intent", v, "live", false)).isInstanceOf(GuardrailViolationException.class);
        assertThatThrownBy(() -> studio.submitForApproval("fulfilment_intent", v, "tune wording", null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> studio.submitForApproval("fulfilment_intent", v, "", "OMS-42")).isInstanceOf(ValidationException.class);

        // The swapped wording misclassifies the free-text golden cases; restoring it scores strictly higher (GR-11 evaluation is wording-sensitive).
        EvaluationReport bad = studio.evaluate("fulfilment_intent", v);
        assertThat(bad.failures()).isNotEmpty();
        studio.edit("fulfilment_intent", v, Map.of(
            "questions.intent.criteria.ship_complete", "Everything together in one delivery, even if it arrives later",
            "questions.intent.criteria.ship_as_available", "Each item as soon as it is ready, in separate shipments"));
        assertThat(definitions.find("fulfilment_intent", v).get().evaluationReport()).isNull();     // edits invalidate stale reports
        EvaluationReport report = studio.evaluate("fulfilment_intent", v);
        assertThat(report.passed()).as("gate=%s failures=%s", report.gateFailures(), report.failures()).isTrue();
        assertThat(report.cases()).isGreaterThanOrEqualTo(12);
        assertThat(report.correct()).isGreaterThan(bad.correct());
        assertThat(report.goldenSet()).isEqualTo("fulfilment_intent_gold");

        var submitted = studio.submitForApproval("fulfilment_intent", v, "tune wording", "OMS-42");
        assertThat(submitted.definition().status()).isEqualTo("pending_approval");
        assertThat(submitted.submittedBy()).isEqualTo("user:alice");
        assertThatThrownBy(() -> studio.edit("fulfilment_intent", v, Map.of("bands.act", 0.9))).isInstanceOf(ConflictException.class);

        // Separation of duties: the submitter cannot approve (GR-14).
        assertThatThrownBy(() -> studio.approve("fulfilment_intent", v)).isInstanceOf(ForbiddenException.class);
        actAs("bob", APPROVER);
        var approved = studio.approve("fulfilment_intent", v);
        assertThat(approved.definition().status()).isEqualTo("approved");          // T1: one approver
        assertThat(approved.approvals()).hasSize(1);

        var canary = studio.publish("fulfilment_intent", v, "canary:20", false);
        assertThat(canary.definition().status()).isEqualTo("canary");
        assertThat(canary.definition().canaryPercent()).isEqualTo(20);
        assertThat(definitions.find("fulfilment_intent", seeded).get().definition().status()).isEqualTo("live");    // previous live stays live

        var live = studio.publish("fulfilment_intent", v, "live", false);
        assertThat(live.definition().status()).isEqualTo("live");
        assertThat(definitions.find("fulfilment_intent", seeded).get().definition().status()).isEqualTo("retired");
        assertThat(decisions.decide("fulfilment_intent", intentInput("x", "after"), DecisionOptions.live("order", "after", Actor.system("t"))).version()).isEqualTo(v);

        var back = studio.rollback("fulfilment_intent");
        assertThat(back.definition().version()).isEqualTo(seeded);
        assertThat(back.definition().status()).isEqualTo("live");
        assertThat(definitions.find("fulfilment_intent", v).get().definition().status()).isEqualTo("retired");
        assertThat(events("config", "decision:fulfilment_intent", "decision.definition.rolled_back")).isNotEmpty();
        assertThat(events("config", "decision:fulfilment_intent", "decision.definition.published")).isNotEmpty();
    }

    @Test
    void tierTwoNeedsTwoDistinctApproversAndPassingEvaluation() {
        actAs("alice", EDITOR);
        DecisionDefinition draft = studio.createDraft("return_risk", TestYaml.demo("return_risk"));
        int v = draft.version();
        studio.submitForApproval("return_risk", v, "re-tune rubric", "OMS-7");
        actAs("bob", APPROVER);
        assertThat(studio.approve("return_risk", v).definition().status()).isEqualTo("pending_approval");
        assertThatThrownBy(() -> studio.approve("return_risk", v)).isInstanceOf(ConflictException.class);
        actAs("carol", APPROVER);
        var approved = studio.approve("return_risk", v);
        assertThat(approved.definition().status()).isEqualTo("approved");
        assertThat(approved.approvals()).hasSize(2);
        // Evaluation configured but never run → publish blocked (GR-11).
        assertThatThrownBy(() -> studio.publish("return_risk", v, "shadow", false)).isInstanceOf(GuardrailViolationException.class).hasMessageContaining("Evaluation");
        EvaluationReport report = studio.evaluate("return_risk", v);
        assertThat(report.passed()).as("gate failures: %s, case failures: %s", report.gateFailures(), report.failures()).isTrue();
        assertThat(studio.publish("return_risk", v, "shadow", false).definition().status()).isEqualTo("shadow");
        assertThat(definitions.byStatus("return_risk", "live")).as("previous live version stays live while v%s is in shadow", v).isNotEmpty().allSatisfy(row -> assertThat(row.definition().version()).isNotEqualTo(v));
    }

    @Test
    void emergencyPublishRequiresAdminAndSchedulesRetroReview() {
        actAs("alice", EDITOR);
        int v = studio.createDraft("injection_screen", TestYaml.demo("injection_screen")).version();
        assertThatThrownBy(() -> studio.publish("injection_screen", v, "live", true)).isInstanceOf(ForbiddenException.class);
        actAs("admin", Set.of("*"));
        var row = studio.publish("injection_screen", v, "live", true);
        assertThat(row.definition().status()).isEqualTo("live");
        assertThat(row.retroReviewDueAt()).isNotNull();
        assertThat(row.rollout()).containsEntry("emergency", true);
        assertThat(events("config", "decision:injection_screen", "decision.definition.emergency_published")).isNotEmpty();
    }

    @Test
    void killSwitchAndNonHumanActorsAreRejected() {
        studio.killSwitch("backorder_next_action", true);
        assertThat(config.getBoolean(ConfigKeys.DECISION_KILL_SWITCH_PREFIX + "backorder_next_action", false)).isTrue();
        var r = decisions.decide("backorder_next_action", Map.of("line", Map.of("sku", "A", "days_backordered", 5)), DecisionOptions.live("line", "l1", Actor.system("t")));
        assertThat(r.layer()).isEqualTo(Layer.DEFAULT);
        assertThat(r.value()).isEqualTo("wait");
        studio.killSwitch("backorder_next_action", false);
        assertThat(decisions.decide("backorder_next_action", Map.of("line", Map.of("sku", "A", "substitute_available", true)), DecisionOptions.live("line", "l2", Actor.system("t"))).layer()).isNotEqualTo(Layer.DEFAULT);

        actAsAgent("shopbot");
        assertThatThrownBy(() -> studio.createDraft("backorder_next_action", TestYaml.demo("backorder_next_action"))).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> studio.killSwitch("backorder_next_action", true)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void lintBlocksSubmissionOfDefinitionsWithErrors() {
        actAs("alice", EDITOR);
        String yaml = TestYaml.demo("backorder_next_action").replace("    - customer.tier", "    - customer.tier\n    - customer.age").replace("tier: T1", "tier: T2");
        int v = studio.createDraft("backorder_next_action", yaml).version();
        assertThatThrownBy(() -> studio.submitForApproval("backorder_next_action", v, "r", "T-1")).isInstanceOf(GuardrailViolationException.class).hasMessageContaining("Lint");
        assertThat(studio.lint("backorder_next_action", v).issues()).anyMatch(i -> i.code().equals("protected_attribute") && i.severity().equals("error"));
    }
}
