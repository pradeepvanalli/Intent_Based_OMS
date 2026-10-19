package ai.intentoms.learning.promotion;

import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.Segment;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.LearnedFactCache;
import ai.intentoms.learning.store.LearnedFactRepository;
import ai.intentoms.learning.store.SegmentRepository;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.pii.Tokenizer;
import ai.intentoms.testing.PostgresTestBase;
import ai.intentoms.testing.TestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** GV-06: crypto-shredding the PII key removes what learning derived from tokenized identities, and only that. */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class CryptoShredPropagationIntegrationTest extends PostgresTestBase {
    @Autowired Tokenizer tokenizer;
    @Autowired LearnedFactRepository facts;
    @Autowired LearnedFactCache cache;
    @Autowired SegmentRepository segments;
    @Autowired CryptoShredPropagation propagation;

    private void fact(SubjectType st, String id, FactKey key, TypedValue v) {
        facts.insert(new LearnedFact(Ids.prefixed("fact"), st, id, key, v, 0.7, 20, "test", 1, Instant.now(), null, Instant.now()));
    }

    /** A fact written the way builds before the erased-subject check did: straight to the table. */
    private void legacyFact(String customer) {
        jdbc.sql("""
            insert into learned_fact(fact_id, tenant_id, subject_type, subject_id, fact_key, value, confidence, sample_size, learner, version, valid_from, created_at)
            values (:id, :t, 'CUSTOMER', :c, 'PICKUP_PREFERENCE', '{"type":"BoolValue","value":true}'::jsonb, 0.7, 20, 'legacy', 1, now(), now())
            """).param("id", Ids.prefixed("fact")).param("t", TENANT).param("c", customer).update();
        cache.invalidateAll();
    }

    @Test
    void tokenizerShredDeletesCustomerAndAddressFactsAndRecountsSegments() {
        String customer = tokenizer.email("shred-me-" + Ids.shortId() + "@example.com");
        String node = "NODE-" + Ids.shortId();
        String segmentId = Ids.prefixed("seg");
        segments.insert(new Segment(segmentId, 1, "Split averse", "test", Map.of(), 1, "system:test", Instant.now()));
        fact(SubjectType.CUSTOMER, customer, FactKey.SPLIT_TOLERANCE, new TypedValue.NumberValue(0.2));
        fact(SubjectType.CUSTOMER, customer, FactKey.SEGMENT_MEMBERSHIP, new TypedValue.TextValue(segmentId));
        fact(SubjectType.ADDRESS, tokenizer.address("1 Main St"), FactKey.ADDRESS_REUSE_COUNT, new TypedValue.NumberValue(3));
        fact(SubjectType.NODE, node, FactKey.NODE_ON_TIME_RATE, new TypedValue.NumberValue(0.95));
        assertThat(cache.active(SubjectType.CUSTOMER, customer, FactKey.SPLIT_TOLERANCE)).isPresent();   // warm the live cache

        tokenizer.shred(TENANT);

        assertThat(facts.activeForSubject(SubjectType.CUSTOMER, customer)).isEmpty();
        assertThat(cache.active(SubjectType.CUSTOMER, customer, FactKey.SPLIT_TOLERANCE)).isEmpty();
        assertThat(jdbc.sql("select count(*) from learned_fact where tenant_id = :t and subject_type in ('CUSTOMER','ADDRESS')").param("t", TENANT).query(Long.class).single()).isZero();
        assertThat(facts.active(SubjectType.NODE, node, FactKey.NODE_ON_TIME_RATE)).isPresent();
        assertThat(segments.list()).filteredOn(s -> s.segmentId().equals(segmentId)).singleElement().extracting(Segment::size).isEqualTo(0);
        assertThat(jdbc.sql("select count(*) from event where tenant_id = :t and type = 'learning.shred_propagated'").param("t", TENANT).query(Long.class).single()).isPositive();

        // Tokenisation keeps working with a fresh key; the old token no longer matches.
        assertThat(tokenizer.email("shred-me@example.com")).isNotNull();
    }

    @Test
    void singleCustomerPurgeLeavesOtherCustomersAlone() {
        String gone = "cust-gone-" + Ids.shortId(), kept = "cust-kept-" + Ids.shortId();
        fact(SubjectType.CUSTOMER, gone, FactKey.PICKUP_PREFERENCE, new TypedValue.BoolValue(true));
        fact(SubjectType.CUSTOMER, kept, FactKey.PICKUP_PREFERENCE, new TypedValue.BoolValue(false));
        Map<String, Object> result = propagation.purgeSubject(gone, "user:dpo");
        assertThat(result).containsEntry("customerFactsDeleted", 1);
        assertThat(facts.activeForSubject(SubjectType.CUSTOMER, gone)).isEmpty();
        assertThat(facts.activeForSubject(SubjectType.CUSTOMER, kept)).hasSize(1);
    }

    @Test
    void repeatedErasureAlsoPurgesFactsLearnedUnderThePseudonym() {
        String gone = "cust-alias-" + Ids.shortId();
        String pseudonym = tokenizer.shredSubject(gone, "user:dpo").pseudonym();
        // The repository now refuses facts for an erased subject (N2), so nothing new lands under the pseudonym ...
        fact(SubjectType.CUSTOMER, pseudonym, FactKey.PICKUP_PREFERENCE, new TypedValue.BoolValue(true));
        assertThat(facts.activeForSubject(SubjectType.CUSTOMER, pseudonym)).isEmpty();
        // ... but an older build wrote them straight to the table after the first erasure.
        legacyFact(pseudonym);
        assertThat(facts.activeForSubject(SubjectType.CUSTOMER, pseudonym)).hasSize(1);
        assertThat(tokenizer.shredSubject(gone, "user:dpo").firstTime()).isFalse();
        assertThat(facts.activeForSubject(SubjectType.CUSTOMER, pseudonym)).isEmpty();
        // A direct purge by the raw reference finds the pseudonym in pii_erasure.
        legacyFact(pseudonym);
        assertThat(propagation.purgeSubject(gone, "user:dpo")).containsEntry("customerFactsDeleted", 1).containsEntry("pseudonymPurged", true);
    }
}
