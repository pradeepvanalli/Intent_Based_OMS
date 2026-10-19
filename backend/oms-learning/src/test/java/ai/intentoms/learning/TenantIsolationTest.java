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
            EpisodeStatus.MATURE, 0.8, Map.of("otd", 0.8), 1, Instant.now(), Instant.now(), "order", "order-a", null, 1));
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

    @Autowired ai.intentoms.learning.learner.NodeReliabilityLearner nodeReliability;
    @Autowired ai.intentoms.learning.learner.CiPreferenceLearner preferences;

    /**
     * NF-06 through the real learners: the same order id, node id and customer ref get opposite outcomes in two tenants,
     * fed as ledger events; each tenant's learned facts reflect only its own outcomes.
     */
    @Test
    void outcomesFedThroughRealLearnersNeverCrossTenants() {
        String tenantB = "tenant-iso-" + Ids.shortId().toLowerCase();
        jdbc.sql("insert into tenant(tenant_id, name) values (:t, :t) on conflict do nothing").param("t", tenantB).update();
        String order = "ord-iso-" + Ids.shortId();
        String node = "DC-ISO-" + Ids.shortId();
        String customer = "cust-iso-" + Ids.shortId();
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);

        // Tenant A: express shopper; the node shipped late (promised yesterday, delivered today).
        feed(TENANT, order, node, customer, "express", today.minusDays(1));
        // Tenant B: same ids, ground shopper; the node was on time (promised tomorrow, delivered today).
        feed(tenantB, order, node, customer, "ground", today.plusDays(1));

        double aOnTime = number(TENANT, SubjectType.NODE, node, FactKey.NODE_ON_TIME_RATE);
        double bOnTime = number(tenantB, SubjectType.NODE, node, FactKey.NODE_ON_TIME_RATE);
        assertThat(aOnTime).isLessThan(bOnTime);
        double aSpeed = number(TENANT, SubjectType.CUSTOMER, customer, FactKey.SPEED_VS_COST);
        double bSpeed = number(tenantB, SubjectType.CUSTOMER, customer, FactKey.SPEED_VS_COST);
        assertThat(aSpeed).isGreaterThan(0.0);
        assertThat(bSpeed).isLessThan(0.0);

        // Feeding more on-time deliveries in B never moves A's facts.
        feed(tenantB, order + "-2", node, customer, "ground", today.plusDays(2));
        assertThat(number(TENANT, SubjectType.NODE, node, FactKey.NODE_ON_TIME_RATE)).isEqualTo(aOnTime);
        assertThat(number(TENANT, SubjectType.CUSTOMER, customer, FactKey.SPEED_VS_COST)).isEqualTo(aSpeed);
    }

    private void feed(String tenant, String order, String node, String customer, String shipMethod, java.time.LocalDate eta) {
        Instant now = Instant.now();
        preferences.on(event(tenant, order, "order.captured", Map.of("orderId", order, "customerRef", customer, "shipMethod", shipMethod, "orderType", "standard_ship",
            "channel", "web"), now.minusSeconds(7200)));
        nodeReliability.on(event(tenant, order, "order.sourced", Map.of("orderId", order, "shipMethod", shipMethod,
            "packages", java.util.List.of(Map.of("nodeId", node, "eta", eta.toString(), "skuClass", "apparel"))), now.minusSeconds(3600)));
        nodeReliability.on(event(tenant, order, "order.delivered", Map.of("orderId", order), now));
    }

    private static ai.intentoms.platform.ledger.Event event(String tenant, String order, String type, Map<String, Object> payload, Instant at) {
        return new ai.intentoms.platform.ledger.Event(Ids.id(), tenant, "order", order, 1, type, payload, ai.intentoms.platform.ledger.Actor.system("test"), null, order, Map.of(), at);
    }

    private double number(String tenant, SubjectType type, String subject, FactKey key) {
        // NF-07: customers are keyed by the vault token of their reference, which differs per tenant (per-tenant keys).
        return TenantContext.supplyAs(tenant, () -> ((TypedValue.NumberValue) facts.active(type, type == SubjectType.CUSTOMER ? customerSubjects.key(subject) : subject, key)
            .orElseThrow().value()).value());
    }

    @org.springframework.beans.factory.annotation.Autowired ai.intentoms.learning.store.CustomerSubjects customerSubjects;
}
