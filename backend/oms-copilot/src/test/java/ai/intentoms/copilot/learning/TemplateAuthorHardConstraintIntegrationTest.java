package ai.intentoms.copilot.learning;

import ai.intentoms.copilot.NoLlmTestBase;
import ai.intentoms.decision.definition.DecisionDefinitionRepository;
import ai.intentoms.decision.studio.DecisionStudioService;
import ai.intentoms.decision.studio.PromptVersionService;
import ai.intentoms.learning.event.LearningEvents;
import ai.intentoms.learning.model.Direction;
import ai.intentoms.learning.model.Hypothesis;
import ai.intentoms.learning.model.Proposal;
import ai.intentoms.learning.model.ProposalStatus;
import ai.intentoms.learning.model.WeightLever;
import ai.intentoms.learning.promotion.HardConstraints;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D4-3 (audit 4, §7 hard constraints, carried from audit 3): the template author marked its own {@code WeightChange}
 * proposals EVALUATED directly ({@code TemplateAuthor.draftWeights}), the one proposal path that never entered
 * {@link ai.intentoms.simulation.learning.ProposalEvaluationService#evaluate}, where {@link HardConstraints#check}
 * otherwise always runs before a proposal is traded off against reward. Fixed: {@code draftWeights} runs the same
 * check itself before marking a proposal evaluated, and rejects it with the violated constraint's reason when it
 * fails.
 *
 * <p>Uses a fake {@link WeightLever.Evidence} (not a real learner's) so the hard-constraint violation can be crafted
 * directly: a weight key whose name matches a daily action/dollar cap (HC-4) and whose proposed value raises it.
 */
class TemplateAuthorHardConstraintIntegrationTest extends NoLlmTestBase {
    @Autowired DecisionStudioService studio;
    @Autowired DecisionDefinitionRepository definitions;
    @Autowired HypothesisRepository hypotheses;
    @Autowired ProposalRepository proposals;
    @Autowired PromptVersionService prompts;
    @Autowired LearningEvents events;
    @Autowired HardConstraints hardConstraints;

    private String tenant;

    @BeforeEach
    void ownTenant() {
        tenant = "d4-3-" + Ids.shortId().toLowerCase();
        jdbc.sql("insert into tenant(tenant_id, name) values (:t, :t) on conflict do nothing").param("t", tenant).update();
        TenantContext.set(tenant);
    }

    @AfterEach
    void back() { TenantContext.set(TENANT); }

    /** A weight key matching {@code HardConstraints}' daily-cap pattern ("...action...cap..."), raised from 0.1 to 0.5. */
    private static final String CONFIG_KEY = "ops.action_caps";
    private static final String WEIGHT_KEY = "action_cap";

    private static final WeightLever.Evidence FAKE_EVIDENCE = new WeightLever.Evidence() {
        @Override public boolean supports(String configKey) { return CONFIG_KEY.equals(configKey); }
        @Override public Map<String, Double> liveWeights() { return Map.of(WEIGHT_KEY, 0.1); }
        @Override public Map<String, Object> report(Map<String, Double> current, Map<String, Double> proposed) {
            return Map.of("method", "plan_rescoring_replay", "liftEstimate", 0.1);
        }
    };

    private Hypothesis weightHypothesis(double from, double to) {
        WeightLever lever = new WeightLever(CONFIG_KEY, Map.of(WEIGHT_KEY, from), Map.of(WEIGHT_KEY, to), Map.of(), 60, 60);
        Hypothesis h = new Hypothesis(Ids.prefixed("hyp"), Map.of("target", lever.target()), "reward", Direction.INCREASE, lever.toLever(), 0.5,
            List.of(), "raise the action cap", Hypothesis.Status.OPEN, Instant.now());
        hypotheses.insert(h);
        return h;
    }

    @Test
    void aWeightProposalThatRaisesADailyCapIsRejectedByTheHardConstraintCheck() {
        TemplateAuthor author = new TemplateAuthor(studio, definitions, hypotheses, proposals, prompts, List.of(FAKE_EVIDENCE), events, hardConstraints);
        Hypothesis h = weightHypothesis(0.1, 0.5);

        Optional<Proposal> drafted = author.draft(h);

        assertThat(drafted).as("a proposal is created so the rejection and its reason are visible").isPresent();
        Proposal p = drafted.get();
        assertThat(p.status()).as("rejected by the hard-constraint check, never marked EVALUATED (D4-3)").isEqualTo(ProposalStatus.REJECTED);
        assertThat(p.rejectedReason()).as("names the violated constraint").contains("HC-4");
    }

    private static final String HARMLESS_CONFIG_KEY = "ops.quality_weights";
    private static final String HARMLESS_WEIGHT_KEY = "quality_score";

    private static final WeightLever.Evidence HARMLESS_EVIDENCE = new WeightLever.Evidence() {
        @Override public boolean supports(String configKey) { return HARMLESS_CONFIG_KEY.equals(configKey); }
        @Override public Map<String, Double> liveWeights() { return Map.of(HARMLESS_WEIGHT_KEY, 0.1); }
        @Override public Map<String, Object> report(Map<String, Double> current, Map<String, Double> proposed) {
            return Map.of("method", "plan_rescoring_replay", "liftEstimate", 0.1);
        }
    };

    @Test
    void aWeightProposalThatDoesNotBreachAnyHardConstraintIsStillEvaluatedNormally() {
        TemplateAuthor author = new TemplateAuthor(studio, definitions, hypotheses, proposals, prompts, List.of(HARMLESS_EVIDENCE), events, hardConstraints);
        WeightLever lever = new WeightLever(HARMLESS_CONFIG_KEY, Map.of(HARMLESS_WEIGHT_KEY, 0.1), Map.of(HARMLESS_WEIGHT_KEY, 0.5), Map.of(), 60, 60);
        Hypothesis h = new Hypothesis(Ids.prefixed("hyp"), Map.of("target", lever.target()), "reward", Direction.INCREASE, lever.toLever(), 0.5,
            List.of(), "raise quality weight", Hypothesis.Status.OPEN, Instant.now());
        hypotheses.insert(h);

        Optional<Proposal> drafted = author.draft(h);

        assertThat(drafted).isPresent();
        assertThat(drafted.get().status()).as("no hard constraint applies, so it is evaluated as before (not skipped, not wrongly rejected)")
            .isEqualTo(ProposalStatus.EVALUATED);
        assertThat(drafted.get().evaluationReport()).containsEntry("method", "plan_rescoring_replay");
    }
}
