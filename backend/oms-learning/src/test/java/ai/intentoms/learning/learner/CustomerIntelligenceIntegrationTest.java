package ai.intentoms.learning.learner;

import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.runtime.DecisionLearningHooks;
import ai.intentoms.learning.governance.ParameterResolver;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.Segment;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.CustomerEvidenceRepository;
import ai.intentoms.learning.store.LearnedFactCache;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.testing.PostgresTestBase;
import ai.intentoms.testing.TestApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** SOO CI-01..CI-05 through the real Spring context and PostgreSQL. */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class CustomerIntelligenceIntegrationTest extends PostgresTestBase {
    @Autowired CiPreferenceLearner learner;
    @Autowired CiSegmentDiscovery discovery;
    @Autowired CustomerEvidenceRepository evidence;
    @Autowired LearnedFactRepository facts;
    @Autowired LearnedFactCache factCache;
    @Autowired DecisionLearningHooks hooks;
    @Autowired ParameterResolver resolver;

    @AfterEach
    void cleanUp() {
        resolver.revoke(CustomerFeatureFamilies.PREFERENCES, "test");
        factCache.invalidateAll();
    }

    private Event event(String type, String aggregateId, Map<String, Object> payload) {
        return new Event(Ids.id(), TenantContext.require(), "order", aggregateId, 1, type, payload, Actor.system("t"), null, aggregateId, Map.of(), Instant.now());
    }

    private Event captured(String orderId, String customer, String shipMethod, String orderType, String state, int units) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("orderId", orderId);
        p.put("customerRef", customer);
        p.put("channel", "web");
        p.put("orderType", orderType);
        p.put("shipMethod", shipMethod);
        p.put("lines", List.of(Map.of("lineId", "L1", "sku", "S", "qty", units)));
        p.put("shipTo", Map.of("state", state));
        return event("order.captured", orderId, p);
    }

    private Object value(String customer, FactKey key) {
        return facts.active(SubjectType.CUSTOMER, customer, key).map(f -> CustomerFactContext.scalar(f.value())).orElse(null);
    }

    @Test
    void preferencesComeFromPositiveAndNegativeEvidenceAndRedeliveryChangesNothing() {
        String c = "cust-" + Ids.shortId();
        String o1 = "o1-" + c, o2 = "o2-" + c, o3 = "o3-" + c;
        learner.on(captured(o1, c, "express", "standard_ship", "CA", 2));
        Event second = captured(o2, c, "next_day", "standard_ship", "CA", 2);
        learner.on(second);
        learner.on(second);   // redelivered: no extra evidence
        learner.on(captured(o3, c, "standard", "bopis", "CA", 2));

        assertThat(evidence.weights(CustomerEvidenceRepository.CUSTOMER, c, FactKey.SPEED_VS_COST.name(), 90, Instant.now()))
            .hasEntrySatisfying("pos", w -> assertThat(w).isCloseTo(2.0, within(1e-3)))
            .hasEntrySatisfying("neg", w -> assertThat(w).isCloseTo(1.0, within(1e-3)));
        assertThat((Double) value(c, FactKey.SPEED_VS_COST)).isGreaterThan(0.0).isLessThan(1.0);
        assertThat(value(c, FactKey.PICKUP_PREFERENCE)).isEqualTo(false);   // 1 pickup vs 2 home deliveries

        // Split tolerance: o1 split and delivered without complaint (+), o2 split then a WISMO contact (-).
        learner.on(event("order.shipped", o1, Map.of("orderId", o1, "packageCount", 1)));
        learner.on(event("order.shipped", o1, Map.of("orderId", o1, "packageCount", 1)));
        learner.on(event("order.delivered", o1, Map.of("orderId", o1, "onTime", true)));
        assertThat((Double) value(c, FactKey.SPLIT_TOLERANCE)).isGreaterThan(0.5);
        learner.on(event("order.shipped", o2, Map.of("orderId", o2, "packageCount", 2)));
        learner.on(event("cs.wismo", o2, Map.of("orderId", o2, "customerRef", c, "channel", "sms")));
        Map<String, Double> split = evidence.weights(CustomerEvidenceRepository.CUSTOMER, c, FactKey.SPLIT_TOLERANCE.name(), 90, Instant.now());
        assertThat(split.get("pos")).isCloseTo(1.0, within(1e-3));
        assertThat(split.get("neg")).isCloseTo(1.0, within(1e-3));
        assertThat(value(c, FactKey.PREFERRED_CONTACT_CHANNEL)).isEqualTo("sms");
        LearnedFact tolerance = facts.active(SubjectType.CUSTOMER, c, FactKey.SPLIT_TOLERANCE).orElseThrow();
        assertThat(tolerance.sampleSize()).isEqualTo(2);
        assertThat(tolerance.learner()).isEqualTo(CiPreferenceLearner.KEY);

        // A complaint after a delivered split replaces that order's positive evidence.
        learner.on(event("cs.wismo", o1, Map.of("orderId", o1, "channel", "email")));
        Map<String, Double> after = evidence.weights(CustomerEvidenceRepository.CUSTOMER, c, FactKey.SPLIT_TOLERANCE.name(), 90, Instant.now());
        assertThat(after.get("pos")).isCloseTo(0.0, within(1e-3));
        assertThat(after.get("neg")).isCloseTo(2.0, within(1e-3));
        assertThat((Double) value(c, FactKey.SPLIT_TOLERANCE)).isLessThan(0.5);
    }

    @Test
    void evidenceDecaysWithTheHalfLifeAndColdStartLeansOnTheCohort() {
        String old = "old-" + Ids.shortId();
        evidence.add(CustomerEvidenceRepository.CUSTOMER, old, FactKey.SPEED_VS_COST.name(), "pos", 1.0, 90, Instant.now().minus(Duration.ofDays(180)));
        assertThat(evidence.weights(CustomerEvidenceRepository.CUSTOMER, old, FactKey.SPEED_VS_COST.name(), 90, Instant.now()).get("pos")).isCloseTo(0.25, within(1e-3));

        // A cohort that overwhelmingly picks speed pulls a brand-new member's single cost-leaning order toward speed.
        String cohort = "app|large|NY-" + Ids.shortId();
        for (int i = 0; i < 40; i++) evidence.add(CustomerEvidenceRepository.SEGMENT, cohort, FactKey.SPEED_VS_COST.name(), "pos", 1.0, 90, Instant.now());
        double pCohort = CiPreferenceLearner.shrunkShare(0, 1, 40, 0);
        double pAlone = CiPreferenceLearner.shrunkShare(0, 1, 0, 0);
        assertThat(pCohort).isGreaterThan(0.75);
        assertThat(pAlone).isCloseTo((0 + 5 * 0.5) / 6, within(1e-9));
        String fresh = "new-" + Ids.shortId();
        learner.observe(fresh, cohort, FactKey.SPEED_VS_COST, "neg", Instant.now());
        assertThat((Double) value(fresh, FactKey.SPEED_VS_COST)).as("one cost-leaning order, speed-first cohort").isGreaterThan(0.4);
    }

    @Test
    void customerFactsReachDecisionsOnlyWhilePromotedAndAreListedOnTheRecord() {
        String c = "feat-" + Ids.shortId();
        String order = "of-" + c;
        learner.on(captured(order, c, "express", "standard_ship", "TX", 3));
        factCache.invalidateAll();
        DecisionOptions opts = DecisionOptions.live("order", order, Actor.system("t"));
        Map<String, Object> input = Map.of("order", Map.of("channel", "web"), "customer", Map.of("tier", "gold"));

        DecisionLearningHooks.Contribution notLive = hooks.contribute("split_acceptability", input, opts);
        assertThat(notLive.factsUsed()).anySatisfy(u -> {
            assertThat(u).containsEntry("factKey", "SPEED_VS_COST").containsEntry("source", "STATIC").containsEntry("subjectId", c);
        });
        assertThat((Map<String, Object>) notLive.input().get("customer")).doesNotContainKey("facts");

        resolver.promote(CustomerFeatureFamilies.PREFERENCES, CiPreferenceLearner.KEY, -1.0, 1.0, null, null, "test");
        DecisionLearningHooks.Contribution live = hooks.contribute("split_acceptability", input, opts);
        @SuppressWarnings("unchecked") Map<String, Object> customer = (Map<String, Object>) live.input().get("customer");
        assertThat(customer).containsEntry("tier", "gold");
        @SuppressWarnings("unchecked") Map<String, Object> served = (Map<String, Object>) customer.get("facts");
        assertThat(served).containsKeys("speed_vs_cost", "pickup_preference");
        assertThat((Map<String, Object>) input.get("customer")).as("the caller's input is never mutated").doesNotContainKey("facts");
        assertThat(hooks.contribute("payment_risk", input, opts).factsUsed()).as("only decisions that read customer facts").isEmpty();
    }

    @Test
    void kMeansDiscoversBehaviouralSegmentsAndRecordsMembership() {
        String run = Ids.shortId();
        Instant now = Instant.now();
        for (int i = 0; i < 80; i++) {
            String c = "seg-" + run + "-" + i;
            boolean fast = i < 40;
            put(c, FactKey.SPLIT_TOLERANCE, new TypedValue.NumberValue(fast ? 0.85 + (i % 5) * 0.01 : 0.15 + (i % 5) * 0.01), now);
            put(c, FactKey.SPEED_VS_COST, new TypedValue.NumberValue(fast ? 0.8 : -0.8), now);
            put(c, FactKey.PICKUP_PREFERENCE, new TypedValue.BoolValue(false), now);
        }
        List<Segment> segments = discovery.discoverForTenant();
        assertThat(segments).hasSizeGreaterThanOrEqualTo(2);
        assertThat(segments).allSatisfy(s -> {
            assertThat(s.size()).isGreaterThanOrEqualTo(30);
            assertThat(s.definition()).containsEntry("method", "kmeans");
            assertThat(s.label()).endsWith("customers");
        });
        Object a = value("seg-" + run + "-0", FactKey.SEGMENT_MEMBERSHIP);
        Object b = value("seg-" + run + "-79", FactKey.SEGMENT_MEMBERSHIP);
        assertThat(a).isNotNull().isNotEqualTo(b);
        assertThat(value("seg-" + run + "-1", FactKey.SEGMENT_MEMBERSHIP)).isEqualTo(a);
    }

    /**
     * CI-01 evidence beyond the order itself: a complaint ({@code cs.complaint}) on a split order, a later ship-method
     * upgrade or downgrade, and a return of a split order because part of it arrived late. Each cohort gets its own fact.
     */
    @Test
    void complaintsUpgradesAndReturnsAreEvidenceAndEachSegmentGetsItsOwnFacts() {
        String c = "cust-" + Ids.shortId();
        String state = "Z" + Ids.shortId().substring(0, 3).toUpperCase();
        String o1 = "o1-" + c, o2 = "o2-" + c, o3 = "o3-" + c;
        learner.on(captured(o1, c, "standard", "standard_ship", state, 2));
        learner.on(captured(o2, c, "standard", "standard_ship", state, 2));
        learner.on(captured(o3, c, "standard", "standard_ship", state, 2));

        // A complaint on a split order counts against split tolerance (defect 24: it used to be ignored).
        learner.on(event("order.shipped", o1, Map.of("orderId", o1, "packageCount", 2)));
        learner.on(event("cs.complaint", o1, Map.of("orderId", o1, "customerRef", c, "channel", "chat", "reason", "complaint")));
        assertThat(evidence.weights(CustomerEvidenceRepository.CUSTOMER, c, FactKey.SPLIT_TOLERANCE.name(), 90, Instant.now()).get("neg")).isCloseTo(1.0, within(1e-3));
        assertThat(value(c, FactKey.PREFERRED_CONTACT_CHANNEL)).isEqualTo("chat");

        // Upgrading two orders to next-day after checkout outweighs the three standard choices' cost evidence... partly.
        Event upgrade = event("order.modified", o2, Map.of("orderId", o2, "fromShipMethod", "standard", "shipMethod", "next_day"));
        learner.on(upgrade);
        learner.on(upgrade); // redelivered: counted once
        learner.on(event("order.modified", o3, Map.of("orderId", o3, "fromShipMethod", "ground", "shipMethod", "express")));
        Map<String, Double> speed = evidence.weights(CustomerEvidenceRepository.CUSTOMER, c, FactKey.SPEED_VS_COST.name(), 90, Instant.now());
        assertThat(speed.get("pos")).isCloseTo(2.0, within(1e-3));
        assertThat(speed.get("neg")).isCloseTo(3.0, within(1e-3));
        // A downgrade is evidence for cost.
        learner.on(event("order.modified", o3, Map.of("orderId", o3, "fromShipMethod", "express", "shipMethod", "standard")));
        assertThat(evidence.weights(CustomerEvidenceRepository.CUSTOMER, c, FactKey.SPEED_VS_COST.name(), 90, Instant.now()).get("neg")).isCloseTo(4.0, within(1e-3));

        // A split order returned because part of it arrived late: speed mattered, and the split did not work for them.
        learner.on(event("order.shipped", o2, Map.of("orderId", o2, "packageCount", 2)));
        learner.on(event("order.delivered", o2, Map.of("orderId", o2, "allDelivered", true)));
        assertThat(evidence.weights(CustomerEvidenceRepository.CUSTOMER, c, FactKey.SPLIT_TOLERANCE.name(), 90, Instant.now()).get("pos")).isCloseTo(1.0, within(1e-3));
        learner.on(new Event(Ids.id(), TenantContext.require(), "return", "rma-" + o2, 1, "return.requested",
            Map.of("orderId", o2, "customerRef", c, "reasonCode", "arrived_late", "lines", List.of(Map.of("lineId", "L1", "reasonCode", "arrived_late"))),
            Actor.system("t"), null, o2, Map.of(), Instant.now()));
        Map<String, Double> split = evidence.weights(CustomerEvidenceRepository.CUSTOMER, c, FactKey.SPLIT_TOLERANCE.name(), 90, Instant.now());
        assertThat(split.get("pos")).isCloseTo(0.0, within(1e-3));
        assertThat(split.get("neg")).isCloseTo(2.0, within(1e-3));
        assertThat(evidence.weights(CustomerEvidenceRepository.CUSTOMER, c, FactKey.SPEED_VS_COST.name(), 90, Instant.now()).get("pos")).isCloseTo(3.0, within(1e-3));

        // Per segment: the cohort (web | small basket | state) has its own typed facts.
        String segment = CiPreferenceLearner.segmentSubject("web|small|" + state);
        LearnedFact segSplit = facts.active(SubjectType.SEGMENT, segment, FactKey.SPLIT_TOLERANCE).orElseThrow();
        assertThat(((TypedValue.NumberValue) segSplit.value()).value()).isCloseTo((0 + 1.0) / (0 + 2 + 2), within(1e-3));
        assertThat(segSplit.learner()).isEqualTo(CiPreferenceLearner.KEY);
        assertThat(((TypedValue.NumberValue) facts.active(SubjectType.SEGMENT, segment, FactKey.SPEED_VS_COST).orElseThrow().value()).value())
            .isCloseTo(2 * ((3 + 1.0) / (3 + 4 + 2)) - 1, within(1e-3));
        assertThat(facts.active(SubjectType.SEGMENT, segment, FactKey.PREFERRED_CONTACT_CHANNEL).map(f -> ((TypedValue.TextValue) f.value()).value())).contains("chat");
        assertThat(facts.active(SubjectType.SEGMENT, segment, FactKey.PICKUP_PREFERENCE).map(f -> ((TypedValue.BoolValue) f.value()).value())).contains(false);
    }

    /** CI-02: without an analyst the template names segments and the provenance says so; every segment has typed traits. */
    @Test
    void segmentProvenanceIsTruthfulAndLabelsAreTyped() {
        String run = Ids.shortId();
        Instant now = Instant.now();
        for (int i = 0; i < 80; i++) {
            String c = "segp-" + run + "-" + i;
            boolean fast = i < 40;
            put(c, FactKey.SPLIT_TOLERANCE, new TypedValue.NumberValue(fast ? 0.9 : 0.1), now);
            put(c, FactKey.SPEED_VS_COST, new TypedValue.NumberValue(fast ? 0.8 : -0.8), now);
        }
        List<Segment> segments = discovery.discoverForTenant();
        assertThat(segments).isNotEmpty().allSatisfy(s -> {
            assertThat(s.definition()).containsEntry("namedBy", "template").containsEntry("templateReason", "no analyst configured");
            assertThat((List<?>) s.definition().get("traits")).isNotEmpty().allSatisfy(t -> assertThat(SegmentNamer.TRAITS).contains(String.valueOf(t)));
        });
    }

    private void put(String customer, FactKey key, TypedValue v, Instant at) {
        facts.supersede(SubjectType.CUSTOMER, customer, key, at);
        facts.insert(new LearnedFact(Ids.prefixed("fact"), SubjectType.CUSTOMER, customer, key, v, 0.8, 5, CiPreferenceLearner.KEY, 1, at, null, at));
    }
}
