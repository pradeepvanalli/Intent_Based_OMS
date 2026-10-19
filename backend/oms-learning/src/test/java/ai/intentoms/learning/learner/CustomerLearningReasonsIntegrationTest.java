package ai.intentoms.learning.learner;

import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.runtime.DecisionLearningHooks;
import ai.intentoms.learning.api.CustomerIntelligenceController;
import ai.intentoms.learning.experiment.ExperimentManager;
import ai.intentoms.learning.experiment.ExperimentTrafficRouter;
import ai.intentoms.learning.governance.ParameterResolver;
import ai.intentoms.learning.model.Experiment;
import ai.intentoms.learning.model.ExperimentStatus;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.Segment;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.CustomerSubjects;
import ai.intentoms.learning.store.LearnedFactCache;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.testing.PostgresTestBase;
import ai.intentoms.testing.TestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit 3 C-N7: a segment discovery run that versions nothing says why, in its stored run result
 * ({@code GET /api/v1/learning/segments/last-run}). C-N8: a holdout customer's STATIC facts, typed or numeric, all give
 * the holdout reason, not "not promoted for live use".
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class CustomerLearningReasonsIntegrationTest extends PostgresTestBase {
    @Autowired CiSegmentDiscovery discovery;
    @Autowired CustomerIntelligenceController api;
    @Autowired LearnedFactRepository facts;
    @Autowired LearnedFactCache factCache;
    @Autowired DecisionLearningHooks hooks;
    @Autowired ParameterResolver resolver;
    @Autowired ExperimentManager experiments;
    @Autowired ExperimentTrafficRouter router;
    @Autowired CustomerSubjects subjects;

    private void ownTenant() {
        String tenant = "seg_" + Ids.shortId().toLowerCase();
        jdbc.sql("insert into tenant(tenant_id, name) values (:t, :t) on conflict do nothing").param("t", tenant).update();
        TenantContext.set(tenant);
    }

    private void put(String customer, FactKey key, TypedValue v) {
        Instant now = Instant.now();
        facts.insert(new LearnedFact(Ids.prefixed("fact"), SubjectType.CUSTOMER, customer, key, v, 0.8, 5, CiPreferenceLearner.KEY, 1, now, null, now));
    }

    @Test
    void aDiscoveryRunThatVersionsNothingRecordsWhy() {
        ownTenant();
        try {
            for (int i = 0; i < 5; i++) put("cu_" + String.format("%020x", i + 1), FactKey.SPLIT_TOLERANCE, new TypedValue.NumberValue(0.5));
            List<Segment> written = discovery.discoverForTenant();
            assertThat(written).isEmpty();
            Map<String, Object> last = api.lastSegmentRun();
            assertThat(last).containsEntry("outcome", "nothing_versioned").containsEntry("customers", 5).containsKey("at");
            assertThat((String) last.get("reason")).contains("too few customers").contains("need 60");

            // 70 customers, 65 alike and 5 apart: the small cluster folds into the big one, so one segment is left.
            for (int i = 5; i < 70; i++) put("cu_" + String.format("%020x", i + 1), FactKey.SPLIT_TOLERANCE, new TypedValue.NumberValue(i < 65 ? 0.9 : 0.1));
            assertThat(discovery.discoverForTenant()).isEmpty();
            Map<String, Object> folded = api.lastSegmentRun();
            assertThat(folded).containsEntry("outcome", "nothing_versioned").containsEntry("customers", 70);
            assertThat((String) folded.get("reason")).contains("fewer than two clusters");
        } finally {
            TenantContext.set(TENANT);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyStaticFactOfAHoldoutCustomerGivesTheHoldoutReason() {
        resolver.promote(CustomerFeatureFamilies.PREFERENCES, CiPreferenceLearner.KEY, -1.0, 1.0, null, null, "test");
        Experiment holdout = experiments.ensureHoldout(0.5, "user:test");
        router.invalidate();
        try {
            String held = null;
            for (int i = 0; i < 400 && held == null; i++) {
                String ref = "cust-hold-" + Ids.shortId();
                if (router.inHoldout("customer:" + ref)) held = ref;
            }
            assertThat(held).isNotNull();
            String subject = subjects.key(held);
            put(subject, FactKey.SPEED_VS_COST, new TypedValue.NumberValue(-0.5));
            put(subject, FactKey.PICKUP_PREFERENCE, new TypedValue.BoolValue(false));
            put(subject, FactKey.PREFERRED_CONTACT_CHANNEL, new TypedValue.TextValue("chat"));
            factCache.invalidateAll();

            Map<String, Object> input = Map.of("order", Map.of("channel", "web"), "customer", Map.of("ref", held, "tier", "gold"));
            DecisionLearningHooks.Contribution c = hooks.contribute("fulfilment_intent", input,
                new DecisionOptions("order", "ord_" + Ids.shortId(), null, ai.intentoms.decision.model.Mode.LIVE, null, Actor.system("t"), Map.of("customerRef", held)));
            assertThat(c.factsUsed()).hasSize(3).allSatisfy(u -> {
                assertThat(u).containsEntry("source", "STATIC").containsEntry("subjectId", subject);
                assertThat((String) u.get("reason")).as("fact %s", u.get("factKey")).contains("holdout");
            });
            assertThat((Map<String, Object>) c.input().get("customer")).as("nothing served to a holdout customer").doesNotContainKey("facts");
        } finally {
            experiments.stop(holdout.experimentId(), ExperimentStatus.STOPPED, Map.of(), Actor.parse("user:test"));
            resolver.revoke(CustomerFeatureFamilies.PREFERENCES, "test");
            factCache.invalidateAll();
        }
    }

    @Autowired ai.intentoms.learning.api.ExperimentController experimentApi;
    @Autowired ai.intentoms.learning.api.LearningController learningApi;

    @Test
    void customersAreAskedForByReferenceAndFoundByToken() {
        String ref = "cust-api-" + Ids.shortId();
        put(subjects.key(ref), FactKey.PREFERRED_CONTACT_CHANNEL, new TypedValue.TextValue("sms"));
        assertThat(learningApi.facts("CUSTOMER", ref, null)).singleElement().satisfies(f -> assertThat(f.subjectId()).isEqualTo(subjects.key(ref)));
        assertThat(learningApi.fact(SubjectType.CUSTOMER, ref, FactKey.PREFERRED_CONTACT_CHANNEL).value()).isEqualTo(new TypedValue.TextValue("sms"));

        Experiment holdout = experiments.ensureHoldout(0.5, "user:test");
        router.invalidate();
        try {
            for (int i = 0; i < 20; i++) {
                String c = "cust-member-" + i;
                assertThat(experimentApi.holdoutMember(c)).containsEntry("experimentId", holdout.experimentId())
                    .containsEntry("inHoldout", router.inHoldout("customer:" + c));
            }
        } finally {
            experiments.stop(holdout.experimentId(), ExperimentStatus.STOPPED, Map.of(), Actor.parse("user:test"));
        }
    }
}
