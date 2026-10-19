package ai.intentoms.learning.promotion;

import ai.intentoms.learning.event.LearningEvents;
import ai.intentoms.learning.store.LearnedFactCache;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.pii.PiiShreddedEvent;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GV-06: crypto-shred deletion propagates to learning. What learning derives from a tokenized identity is the
 * per-customer and per-address facts (preferences, segment membership, address reuse); those are deleted, segment
 * sizes are recounted from the memberships that remain, and the live fact cache is emptied so no decision reads a
 * deleted fact afterwards. Learners then retrain on what is left: customer learners only ever see new events.
 *
 * <p>The preference evidence and order attribution those facts were learned from are deleted too, so a learner never
 * re-learns an erased customer from what was kept. Episodes whose decision was about the customer go; episodes of
 * their orders stay, since they hold order ids, reward numbers and typed outcome fields, never a name, email, address
 * or customer token (NF-07).
 *
 * <p>Triggered by {@link PiiShreddedEvent}: {@link ai.intentoms.platform.pii.Tokenizer#shred} (the tenant's whole PII
 * key is rotated, so every customer- and address-scoped fact goes) or {@link ai.intentoms.platform.pii.Tokenizer#shredSubject}
 * (one customer, from {@code POST /api/v1/admin/customers/{ref}/erase}). Both are ledger events
 * ({@code learning.shred_propagated}).
 */
@Service
public class CryptoShredPropagation {
    private static final Logger log = LoggerFactory.getLogger(CryptoShredPropagation.class);
    private final JdbcClient jdbc;
    private final LearnedFactCache cache;
    private final LearningEvents events;

    public CryptoShredPropagation(JdbcClient jdbc, LearnedFactCache cache, LearningEvents events) {
        this.jdbc = jdbc;
        this.cache = cache;
        this.events = events;
    }

    @EventListener
    public void onShred(PiiShreddedEvent e) {
        TenantContext.runAs(e.tenantId(), () -> {
            if (e.tenantWide()) purgeTenantCustomerFacts(e.by());
            else purgeSubject(e.subjectRef(), e.by());
        });
    }

    @Transactional
    public Map<String, Object> purgeSubject(String customerId) { return purgeSubject(customerId, "system:crypto-shred"); }

    /**
     * One customer: their facts, the preference evidence and order attribution the facts were learned from (so nothing
     * is re-learned about them from what was kept), and episodes whose decision was about the customer themselves.
     * Episodes of their orders stay: they hold order ids and reward numbers only.
     */
    @Transactional
    public Map<String, Object> purgeSubject(String customerId, String by) {
        String t = TenantContext.require();
        int facts = jdbc.sql("delete from learned_fact where tenant_id = :t and subject_type = 'CUSTOMER' and subject_id = :c").param("t", t).param("c", customerId).update();
        int evidence = jdbc.sql("delete from customer_preference_evidence where tenant_id = :t and subject_kind = 'customer' and subject_id = :c")
            .param("t", t).param("c", customerId).update();
        int orderRefs = jdbc.sql("delete from customer_order_ref where tenant_id = :t and customer_ref = :c").param("t", t).param("c", customerId).update();
        jdbc.sql("delete from outcome o using episode e where o.tenant_id = :t and e.tenant_id = :t and o.episode_id = e.episode_id and e.subject_type = 'customer' and e.subject_id = :c")
            .param("t", t).param("c", customerId).update();
        int episodes = jdbc.sql("delete from episode where tenant_id = :t and subject_type = 'customer' and subject_id = :c").param("t", t).param("c", customerId).update();
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("evidenceDeleted", evidence);
        extra.put("orderRefsDeleted", orderRefs);
        extra.put("episodesDeleted", episodes);
        return finish("customer", facts, 0, by, extra);
    }

    /** Tenant-wide purge, matching {@code Tokenizer.shred}'s tenant-level key rotation. */
    @Transactional
    public Map<String, Object> purgeTenantCustomerFacts() { return purgeTenantCustomerFacts("system:crypto-shred"); }

    @Transactional
    public Map<String, Object> purgeTenantCustomerFacts(String by) {
        String t = TenantContext.require();
        int customer = jdbc.sql("delete from learned_fact where tenant_id = :t and subject_type = 'CUSTOMER'").param("t", t).update();
        int address = jdbc.sql("delete from learned_fact where tenant_id = :t and subject_type = 'ADDRESS'").param("t", t).update();
        int evidence = jdbc.sql("delete from customer_preference_evidence where tenant_id = :t and subject_kind = 'customer'").param("t", t).update();
        int orderRefs = jdbc.sql("delete from customer_order_ref where tenant_id = :t").param("t", t).update();
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("evidenceDeleted", evidence);
        extra.put("orderRefsDeleted", orderRefs);
        return finish("tenant", customer, address, by, extra);
    }

    /**
     * Segment sizes are recounted for the current segmentation only, matching each membership fact's segment key
     * ({@code definition.key}, the slug the membership stores) — comparing it with {@code segment_id} zeroed every size.
     */
    private Map<String, Object> finish(String scope, int customerFacts, int addressFacts, String by, Map<String, Object> extra) {
        String t = TenantContext.require();
        int segments = jdbc.sql("""
            update segment s set size = (select count(*) from learned_fact f where f.tenant_id = s.tenant_id and f.subject_type = 'CUSTOMER'
                                         and f.fact_key = 'SEGMENT_MEMBERSHIP' and f.valid_to is null and f.value->>'value' = s.definition->>'key')
            where s.tenant_id = :t and s.version = (select max(version) from segment where tenant_id = :t)
            """).param("t", t).update();
        cache.invalidateAll();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope", scope);
        result.put("customerFactsDeleted", customerFacts);
        result.put("addressFactsDeleted", addressFacts);
        result.putAll(extra);
        result.put("segmentsRecounted", segments);
        events.shredPropagated(Actor.parse(by), result);
        log.info("GV-06: crypto-shred propagated for tenant {} ({}): {}", t, scope, result);
        return result;
    }
}
