package ai.intentoms.copilot.learning;

import ai.intentoms.copilot.NoLlmTestBase;
import ai.intentoms.copilot.objective.ObjectiveDraftService;
import ai.intentoms.copilot.ops.BoundQuery;
import ai.intentoms.copilot.ops.OpsQueryCatalog;
import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.model.Layer;
import ai.intentoms.decision.provider.ProviderRegistry;
import ai.intentoms.decision.runtime.DecisionService;
import ai.intentoms.learning.learner.NodeReliabilityLearner;
import ai.intentoms.learning.model.Direction;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.Hypothesis;
import ai.intentoms.learning.model.ObjectiveProfile;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.service.EpisodeAssembler;
import ai.intentoms.learning.service.ObjectiveService;
import ai.intentoms.learning.store.EpisodeRepository;
import ai.intentoms.learning.store.HypothesisRepository;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * NF-05: with every LLM provider down the order path runs on L0 rules and the heuristic provider, parameter learners
 * keep learning and episodes keep opening; only model drafting (analyst, LLM author) stops, and the objective editor falls back to
 * its keyword template (XP-07). Also proves the XP-06 learning query templates run against the real schema.
 */
class NoLlmLearningDegradationIntegrationTest extends NoLlmTestBase {
    @Autowired ProviderRegistry providers;
    @Autowired DecisionService decisions;
    @Autowired NodeReliabilityLearner nodeLearner;
    @Autowired LearnedFactRepository facts;
    @Autowired EpisodeAssembler assembler;
    @Autowired EpisodeRepository episodes;
    @Autowired AnalystService analyst;
    @Autowired AuthorService author;
    @Autowired HypothesisRepository hypotheses;
    @Autowired ObjectiveDraftService objectiveDrafts;
    @Autowired ObjectiveService objectives;
    @Autowired OpsQueryCatalog catalog;

    @Autowired ai.intentoms.decision.definition.DecisionDefinitionLoader loader;

    private String tenant = TENANT;

    private Event event(String type, String aggregateType, String aggregateId, Map<String, Object> payload) {
        return new Event(Ids.id(), tenant, aggregateType, aggregateId, 1, type, payload, Actor.system("test"), null, aggregateId, Map.of(), Instant.now());
    }

    /** Runs in a tenant of its own so its decisions never change the shared "test" tenant other copilot tests read. */
    @Test
    void decisionsAndParameterLearnersKeepRunningWhileDraftingStops() {
        tenant = "nf05-" + Ids.shortId();
        jdbc.sql("insert into tenant(tenant_id, name) values (:t, :t) on conflict do nothing").param("t", tenant).update();
        TenantContext.set(tenant);
        try {
            loader.loadDirectory(DEMO.resolve("decisions"));
            degradedRun();
        } finally {
            TenantContext.set(TENANT);
        }
    }

    private void degradedRun() {
        assertThat(providers.llm()).isEmpty();

        String orderId = "ord_nf05_" + Ids.shortId();
        DecisionResult intent = decisions.decide("fulfilment_intent", Map.of("order", Map.of("channel", "web", "notes", "ship everything together", "id", orderId, "intent_choices", List.of()),
            "customer", Map.of("tier", "gold")), DecisionOptions.live("order", orderId, Actor.system("nf05")));
        assertThat(intent.provider()).isEqualTo("heuristic");
        assertThat(intent.layer()).isIn(Layer.L1_SYSTEM_ONE, Layer.DEFAULT);
        DecisionResult rule = decisions.decide("appeasement", Map.of("case", Map.of("customer_sentiment", "angry"), "customer", Map.of("tier", "gold",
            "history", Map.of("appeasements_12m", 5, "orders_12m", 9))), DecisionOptions.live("order", orderId, Actor.system("nf05")));
        assertThat(rule.layer()).isEqualTo(Layer.L0_RULE);

        // Episodes keep opening from decision.evaluated.
        assembler.on(event("decision.evaluated", "decision", intent.recordId(), Map.of("decision", "fulfilment_intent")));
        TenantContext.set(tenant);
        assertThat(episodes.findByDecisionId(intent.recordId())).isPresent();

        // A parameter learner keeps updating from outcomes.
        String node = "NF05-" + Ids.shortId();
        nodeLearner.on(event("order.sourced", "order", orderId, Map.of("packages", List.of(Map.of("nodeId", node, "eta", LocalDate.now().plusDays(3).toString())))));
        TenantContext.set(tenant);
        nodeLearner.on(event("order.delivered", "order", orderId, Map.of("onTime", true)));
        TenantContext.set(tenant);
        assertThat(facts.active(SubjectType.NODE, node, FactKey.NODE_ON_TIME_RATE)).isPresent();

        // Only model drafting stops: the analyst finds nothing, and a loose hypothesis ("raise act threshold") is left for a
        // person; the template author only writes what a hypothesis states precisely (TemplateAuthorCriticIntegrationTest).
        assertThat(analyst.runOnce()).isEmpty();
        Hypothesis h = new Hypothesis(Ids.prefixed("hyp"), Map.of("decision", "fulfilment_intent"), "override_rate", Direction.DECREASE,
            Map.of("target", "decision:fulfilment_intent", "description", "raise act threshold"), 0.6, List.of(), "test", Hypothesis.Status.OPEN, Instant.now());
        hypotheses.insert(h);
        assertThat(author.draft(h.hypothesisId())).isEmpty();
    }

    @Test
    void objectiveEditorFallsBackToTheTemplateAndStillNeedsASecondPerson() {
        ObjectiveProfile draft = objectiveDrafts.draft("On-time delivery matters most to us. Shipping cost is less important. Avoid split packages.", "user:tester");
        assertThat(draft.status()).isEqualTo(ObjectiveProfile.Status.DRAFT);
        assertThat(draft.draftMethod()).isEqualTo("template");
        assertThat(draft.weights().otd()).isEqualTo(2.0);
        assertThat(draft.weights().cost()).isEqualTo(0.5);
        assertThat(draft.weights().split()).isGreaterThan(1.0);
        assertThat(draft.rationale().get("otd")).startsWith("raised");
        assertThat(draft.sourceText()).contains("On-time delivery");
        assertThatThrownBy(() -> objectives.approve(draft.version(), "user:tester")).isInstanceOf(ValidationException.class);
        assertThat(objectives.approve(draft.version(), "user:approver").status()).isEqualTo(ObjectiveProfile.Status.APPROVED);
    }

    @Test
    void learningQueryTemplatesRunAgainstTheSchema() {
        for (String name : List.of("learning_proposals_by_status", "learning_recent_promotions", "learner_status", "episode_outcome_coverage", "learned_facts_by_key",
            "learning_experiments")) {
            BoundQuery q = catalog.bind(name, Map.of());
            var spec = jdbc.sql(q.sql());
            for (var e : q.parameters().entrySet()) spec = spec.param(e.getKey(), e.getValue());
            assertThat(spec.query().listOfRows()).as(name).isNotNull();
        }
        assertThat(catalog.match("which learners are paused").template().name()).isEqualTo("learner_status");
        assertThat(catalog.match("how many learning proposals are awaiting review").template().name()).isEqualTo("learning_proposals_by_status");
        // The two example questions the keyword fallback used to misroute (to orders_by_status and decision_band_mix).
        assertThat(catalog.match("What did learning promote this month?").template().name()).isEqualTo("learning_recent_promotions");
        assertThat(catalog.match("What is the outcome coverage per decision?").template().name()).isEqualTo("episode_outcome_coverage");
        // D5-6/XP-06: these two used to fall through to orders_by_status (no template mentioned lift, holdout or experiment).
        assertThat(catalog.match("What is the lift versus holdout?").template().name()).isEqualTo("learning_experiments");
        assertThat(catalog.match("Which experiments are running?").template().name()).isEqualTo("learning_experiments");
    }
}
