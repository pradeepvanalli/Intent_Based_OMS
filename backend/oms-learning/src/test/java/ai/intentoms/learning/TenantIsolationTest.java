package ai.intentoms.learning;

import ai.intentoms.learning.model.Episode;
import ai.intentoms.learning.model.EpisodeStatus;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.EpisodeRepository;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.testing.PostgresTestBase;
import ai.intentoms.testing.TestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** NF-06: one tenant's outcomes must never change, or even be visible to, another tenant's learners. */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class TenantIsolationTest extends PostgresTestBase {
    private static final String OTHER_TENANT = "tenant-b-" + Ids.shortId();

    @Autowired EpisodeRepository episodes;
    @Autowired LearnedFactRepository facts;

    @Test
    void anotherTenantsEpisodesAndFactsAreInvisible() {
        String nodeId = "node-" + Ids.shortId();

        // Tenant A (the base class's bound "test" tenant) writes an episode and a fact for this node id.
        episodes.insert(new Episode(Ids.prefixed("epi"), Ids.prefixed("dr"), "sourcing_node_score", "order-a", null, null,
            EpisodeStatus.MATURE, 0.8, Map.of("otd", 0.8), 1, Instant.now(), Instant.now()));
        facts.insert(new LearnedFact(Ids.prefixed("fact"), SubjectType.NODE, nodeId, FactKey.NODE_ON_TIME_RATE, new TypedValue.NumberValue(0.95),
            0.9, 50, "node_reliability", 1, Instant.now(), null, Instant.now()));

        // Tenant B, same node id, never touches tenant A's rows.
        TenantContext.runAs(OTHER_TENANT, () -> {
            assertThat(facts.active(SubjectType.NODE, nodeId, FactKey.NODE_ON_TIME_RATE)).isEmpty();
            assertThat(episodes.forOrder("order-a")).isEmpty();

            facts.insert(new LearnedFact(Ids.prefixed("fact"), SubjectType.NODE, nodeId, FactKey.NODE_ON_TIME_RATE, new TypedValue.NumberValue(0.10),
                0.9, 50, "node_reliability", 1, Instant.now(), null, Instant.now()));
            var bFact = facts.active(SubjectType.NODE, nodeId, FactKey.NODE_ON_TIME_RATE).orElseThrow();
            assertThat(((TypedValue.NumberValue) bFact.value()).value()).isEqualTo(0.10);
        });

        // Back on tenant A: still sees its own 0.95, unaffected by tenant B's 0.10 for the same node id.
        var aFact = facts.active(SubjectType.NODE, nodeId, FactKey.NODE_ON_TIME_RATE).orElseThrow();
        assertThat(((TypedValue.NumberValue) aFact.value()).value()).isEqualTo(0.95);
        assertThat(episodes.forOrder("order-a")).hasSize(1);
    }
}
