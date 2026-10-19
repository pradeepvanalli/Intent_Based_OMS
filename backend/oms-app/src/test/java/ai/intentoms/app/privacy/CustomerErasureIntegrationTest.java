package ai.intentoms.app.privacy;

import ai.intentoms.IntentOmsApplication;
import ai.intentoms.decision.model.Band;
import ai.intentoms.decision.model.DecisionRecord;
import ai.intentoms.decision.model.Layer;
import ai.intentoms.decision.model.Mode;
import ai.intentoms.decision.record.DecisionRecordRepository;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.platform.api.PrivacyController;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.json.Json;
import ai.intentoms.testing.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GV-06 end to end: erasing one customer through the admin endpoint removes their personal details from orders and
 * decision inputs, deletes what learning derived from them (facts, preference evidence, order attribution, episodes
 * about them), recounts segments correctly, writes a ledger event without the original reference, and is idempotent.
 */
@SpringBootTest(classes = IntentOmsApplication.class, properties = {"oms.demo.seed=false"})
@ActiveProfiles("test")
class CustomerErasureIntegrationTest extends PostgresTestBase {
    @Autowired PrivacyController privacy;
    @Autowired DecisionRecordRepository records;
    @Autowired LearnedFactRepository facts;

    @Test
    void erasingACustomerRemovesTheirPiiAndEverythingLearnedFromThem() {
        String ref = "cust-erase-" + Ids.shortId();
        String other = "cust-keep-" + Ids.shortId();
        String orderId = "ord_erase_" + Ids.shortId();

        jdbc.sql("""
            insert into orders(id, tenant_id, channel, channel_order_id, order_type, customer_ref, customer, state_model_id, state_model_version, status, placed_at, search_text)
            values (:id, :t, 'web', :ch, 'standard_ship', :c, cast(:cust as jsonb), 'order', 1, 'delivered', now(), :search)
            """).param("id", orderId).param("t", TENANT).param("ch", "W-" + orderId).param("c", ref)
            .param("cust", Json.write(Map.of("ref", ref, "name", "Jane Doe", "email", "jane.doe@example.com", "phone", "+1 415 555 0101", "tier", "gold")))
            .param("search", orderId + " Jane Doe jane.doe@example.com").update();
        jdbc.sql("insert into order_address(tenant_id, order_id, type, address) values (:t, :o, 'ship_to', cast(:a as jsonb))").param("t", TENANT).param("o", orderId)
            .param("a", Json.write(Map.of("type", "ship_to", "name", "Jane Doe", "line1", "1 Market St", "city", "San Francisco", "state", "CA", "zip", "94105", "country", "US"))).update();

        Map<String, Object> input = Map.of("customer", Map.of("ref", ref, "name", "Jane Doe", "email", "jane.doe@example.com"), "order", Map.of("id", orderId, "notes", "leave with Jane"));
        String recordId = Ids.id();
        records.insert(new DecisionRecord(recordId, "fulfilment_intent", 1, Mode.LIVE, "order", orderId, orderId, Layer.L1_SYSTEM_ONE, Band.ACT, "act", "ship_complete",
            Map.of(), 0.9, "heuristic", "h", Json.sha256(input), Map.of(), input, List.of(), List.of(), 1, 0, 0, false, null, "system:test", Map.of(),
            null, null, null, null, Map.of(), Instant.now(), Map.of(), List.of("ship_complete"), "ship_complete", 1.0, 1));

        facts.insert(new LearnedFact(Ids.prefixed("fact"), SubjectType.CUSTOMER, ref, FactKey.SPLIT_TOLERANCE, new TypedValue.NumberValue(0.7), 0.6, 4, "ci_preference", 1, Instant.now(), null, Instant.now()));
        facts.insert(new LearnedFact(Ids.prefixed("fact"), SubjectType.CUSTOMER, ref, FactKey.SEGMENT_MEMBERSHIP, new TypedValue.TextValue("happy-to-split"), 0.8, 1, "ci_segment_discovery", 1, Instant.now(), null, Instant.now()));
        facts.insert(new LearnedFact(Ids.prefixed("fact"), SubjectType.CUSTOMER, other, FactKey.SEGMENT_MEMBERSHIP, new TypedValue.TextValue("happy-to-split"), 0.8, 1, "ci_segment_discovery", 1, Instant.now(), null, Instant.now()));
        int version = jdbc.sql("select coalesce(max(version), 0) + 1 from segment where tenant_id = :t").param("t", TENANT).query(Integer.class).single();
        jdbc.sql("insert into segment(segment_id, tenant_id, version, label, definition, size, created_by) values (:id, :t, :v, 'Happy to split', cast(:d as jsonb), 2, 'system:test')")
            .param("id", Ids.prefixed("seg")).param("t", TENANT).param("v", version).param("d", Json.write(Map.of("key", "happy-to-split"))).update();
        jdbc.sql("insert into customer_preference_evidence(tenant_id, subject_kind, subject_id, fact_key, option, weight) values (:t, 'customer', :c, 'SPLIT_TOLERANCE', 'pos', 3)")
            .param("t", TENANT).param("c", ref).update();
        jdbc.sql("insert into customer_order_ref(tenant_id, order_id, customer_ref, cohort) values (:t, :o, :c, 'web|small|CA')").param("t", TENANT).param("o", orderId).param("c", ref).update();
        jdbc.sql("""
            insert into episode(episode_id, tenant_id, decision_id, decision_name, order_id, status, reward_components, policy_version, created_at, subject_type, subject_id,
                                last_decision_id, evaluation_count, decision_point_key)
            values (:e, :t, :d, 'appeasement', null, 'OPEN', '{}'::jsonb, 1, now(), 'customer', :c, :d, 1, :k)
            """).param("e", Ids.prefixed("epi")).param("t", TENANT).param("d", Ids.id()).param("c", ref).param("k", "appeasement|customer|" + ref + "||").update();

        actAs("admin", Set.of("*"));
        Map<String, Object> first = privacy.erase(ref);
        String pseudonym = (String) first.get("pseudonym");
        assertThat(first).containsEntry("firstTime", true).containsEntry("erased", true);
        assertThat(pseudonym).startsWith("cu_").doesNotContain(ref);

        // Orders: no personal details left, reference replaced.
        Map<String, Object> order = jdbc.sql("select customer_ref, customer::text as customer, search_text from orders where tenant_id = :t and id = :id")
            .param("t", TENANT).param("id", orderId).query().singleRow();
        assertThat(order.get("customer_ref")).isEqualTo(pseudonym);
        assertThat(String.valueOf(order.get("customer")) + order.get("search_text")).doesNotContain("Jane").doesNotContain("jane.doe").doesNotContain("555").doesNotContain(ref);
        String address = jdbc.sql("select address::text from order_address where tenant_id = :t and order_id = :o").param("t", TENANT).param("o", orderId).query(String.class).single();
        assertThat(address).doesNotContain("Market").doesNotContain("Jane").doesNotContain("San Francisco").contains("941").contains("CA");

        // Decision input pseudonymised.
        DecisionRecord rec = records.find(recordId).orElseThrow();
        assertThat(Json.write(rec.input())).doesNotContain("Jane").doesNotContain("jane.doe").doesNotContain(ref).contains(pseudonym);

        // Learning: facts, evidence, attribution and episodes about the customer gone; the other customer untouched; segment recounted to 1.
        assertThat(count("select count(*) from learned_fact where tenant_id = :t and subject_type = 'CUSTOMER' and subject_id = :c", ref)).isZero();
        assertThat(count("select count(*) from customer_preference_evidence where tenant_id = :t and subject_id = :c", ref)).isZero();
        assertThat(count("select count(*) from customer_order_ref where tenant_id = :t and customer_ref = :c", ref)).isZero();
        assertThat(count("select count(*) from episode where tenant_id = :t and subject_id = :c", ref)).isZero();
        assertThat(count("select count(*) from learned_fact where tenant_id = :t and subject_type = 'CUSTOMER' and subject_id = :c", other)).isEqualTo(1);
        assertThat(jdbc.sql("select size from segment where tenant_id = :t and version = :v").param("t", TENANT).param("v", version).query(Integer.class).single()).isEqualTo(1);

        // Ledger and audit name the pseudonym, never the reference.
        List<Map<String, Object>> evs = jdbc.sql("select type, payload::text as payload from event where tenant_id = :t and aggregate_type = 'privacy' and aggregate_id = :a")
            .param("t", TENANT).param("a", "customer:" + pseudonym).query().listOfRows();
        assertThat(evs).extracting(e -> e.get("type")).contains("customer.erased");
        assertThat(evs).allSatisfy(e -> assertThat((String) e.get("payload")).doesNotContain(ref));
        assertThat(count("select count(*) from event where tenant_id = :t and aggregate_type = 'learning' and type = 'learning.shred_propagated' and payload::text not like '%' || :c || '%'", ref))
            .isGreaterThanOrEqualTo(1);

        // Idempotent.
        Map<String, Object> again = privacy.erase(ref);
        assertThat(again).containsEntry("firstTime", false).containsEntry("pseudonym", pseudonym);
    }

    @Autowired ai.intentoms.decision.api.DecisionRecordController decisionRecords;

    @Test
    void afterErasureNoLearnedFactAboutTheCustomerSurvivesOnDecisionRecordsOrTheWhyView() {
        String ref = "cust-why-" + Ids.shortId();
        String token = ai.intentoms.platform.tenant.TenantContext.supplyAs(TENANT, () -> customerSubjects.key(ref));
        Map<String, Object> input = Map.of("order", Map.of("id", "ord_why", "notes", List.of()), "customer", Map.of("ref", ref, "tier", "gold"));
        String recordId = Ids.id();
        List<Map<String, Object>> used = List.of(
            Map.of("subjectType", "CUSTOMER", "subjectId", token, "factKey", "SPEED_VS_COST", "learned", -0.94, "source", "STATIC"),
            Map.of("subjectType", "CUSTOMER", "subjectId", ref, "factKey", "PICKUP_PREFERENCE", "learned", false, "source", "STATIC"),
            Map.of("subjectType", "NODE", "subjectId", "DC-NJ", "factKey", "NODE_ON_TIME_RATE", "learned", 0.93, "source", "LEARNED"));
        records.insert(new DecisionRecord(recordId, "fulfilment_intent", 1, Mode.LIVE, "order", "ord_why", "ord_why", Layer.L1_SYSTEM_ONE, Band.ACT, "act", "ship_complete",
            Map.of(), 0.9, "heuristic", "h", Json.sha256(input), Map.of(), input, List.of(), List.of(), 1, 0, 0, false, null, "system:test", Map.of("learnedFacts", used),
            null, null, null, null, Map.of(), Instant.now(), Map.of(), List.of("ship_complete"), "ship_complete", 1.0, 1));

        actAs("admin", Set.of("*"));
        privacy.erase(ref);

        String why = Json.write(decisionRecords.why(recordId, null));
        assertThat(why).as("the why view shows no learned fact about the erased customer").doesNotContain("SPEED_VS_COST").doesNotContain("PICKUP_PREFERENCE")
            .doesNotContain(ref).contains("NODE_ON_TIME_RATE");
        assertThat(count("select count(*) from decision_record where tenant_id = :t and (details::text like '%' || :c || '%' or input::text like '%' || :c || '%')", ref)).isZero();
    }

    @Autowired ai.intentoms.learning.store.CustomerSubjects customerSubjects;

    private long count(String sql, String c) {
        return jdbc.sql(sql).param("t", TENANT).param("c", c).query(Long.class).single();
    }
}
