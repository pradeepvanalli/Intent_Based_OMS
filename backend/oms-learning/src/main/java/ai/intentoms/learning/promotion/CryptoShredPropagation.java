package ai.intentoms.learning.promotion;

import ai.intentoms.learning.event.LearningEvents;
import ai.intentoms.learning.store.LearnedFactCache;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.pii.PiiShreddedEvent;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
            else purgeSubject(e.subjectRef(), e.pseudonym(), e.by());
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
    public Map<String, Object> purgeSubject(String customerId, String by) { return purgeSubject(customerId, null, by); }

    /**
     * Facts can sit under either name: the raw reference (learned before the erasure) or the pseudonym that replaced it
     * on orders (learned by an older build that did not check {@link ai.intentoms.learning.store.ErasedSubjects}). Both
     * are purged, so a repeated erasure removes what was learned in between. The pseudonym comes with the event, or
     * from {@code pii_erasure} when the purge is called directly.
     */
    @Transactional
    public Map<String, Object> purgeSubject(String customerId, String pseudonym, String by) {
        String t = TenantContext.require();
        List<String> names = new ArrayList<>(List.of(customerId));
        String alias = pseudonym != null ? pseudonym : jdbc.sql("select pseudonym from pii_erasure where tenant_id = :t and subject_hash = :h")
            .param("t", t).param("h", Json.sha256(Map.of("tenant", t, "subject", customerId))).query(String.class).optional().orElse(null);
        if (alias != null && !alias.equals(customerId)) names.add(alias);
        int facts = jdbc.sql("delete from learned_fact where tenant_id = :t and subject_type = 'CUSTOMER' and subject_id in (:c)").param("t", t).param("c", names).update();
        int evidence = jdbc.sql("delete from customer_preference_evidence where tenant_id = :t and subject_kind = 'customer' and subject_id in (:c)")
            .param("t", t).param("c", names).update();
        int orderRefs = jdbc.sql("delete from customer_order_ref where tenant_id = :t and customer_ref in (:c)").param("t", t).param("c", names).update();
        jdbc.sql("delete from outcome o using episode e where o.tenant_id = :t and e.tenant_id = :t and o.episode_id = e.episode_id and e.subject_type = 'customer' and e.subject_id in (:c)")
            .param("t", t).param("c", names).update();
        int episodes = jdbc.sql("delete from episode where tenant_id = :t and subject_type = 'customer' and subject_id in (:c)").param("t", t).param("c", names).update();
        // Segment definitions list a few sample members (by vault token since NF-07): an erased customer is not one of them.
        int samples = 0;
        for (Map<String, Object> row : jdbc.sql("select segment_id, definition::text as d from segment where tenant_id = :t").param("t", t).query().listOfRows()) {
            Map<String, Object> def = Json.readMap((String) row.get("d"));
            if (!(def.get("sampleMembers") instanceof List<?> members) || members.stream().noneMatch(m -> names.contains(String.valueOf(m)))) continue;
            Map<String, Object> out = new LinkedHashMap<>(def);
            out.put("sampleMembers", members.stream().filter(m -> !names.contains(String.valueOf(m))).toList());
            jdbc.sql("update segment set definition = :d where tenant_id = :t and segment_id = :id").param("d", ai.intentoms.platform.jdbc.Jsonb.of(out)).param("t", t).param("id", row.get("segment_id")).update();
            samples++;
        }
        int ledger = scrubLearningEvents(t, customerId, alias != null ? alias : subjects == null ? null : subjects.key(customerId), names);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("evidenceDeleted", evidence);
        extra.put("orderRefsDeleted", orderRefs);
        extra.put("episodesDeleted", episodes);
        extra.put("pseudonymPurged", alias != null);
        extra.put("segmentSamplesScrubbed", samples);
        extra.put("learningEventsScrubbed", ledger);
        return finish("customer", facts, 0, by, extra);
    }

    /**
     * GV-06 / C-N4 on the ledger, the one exception to its being append-only (platform migration V111): the customer's
     * existing learning events ({@code fact.learned} and any other event of the {@code learning} aggregate that names
     * them) are rewritten so the raw reference becomes the vault token and a customer fact's learned value is dropped.
     * The event keeps its type, ids, position and time; its payload is marked {@code erased} and its digests are
     * recomputed over what remains (an event later in the same learning stream keeps the chain digest it recorded). The
     * update runs with {@code oms.legal_erasure} set for its own statement and cleared right after; nothing else can
     * update an event. Returns how many events were rewritten.
     */
    int scrubLearningEvents(String t, String ref, String token, List<String> names) {
        if (token == null) return 0;
        List<Map<String, Object>> rows = jdbc.sql("""
            select event_id, type, payload::text as payload from event
            where tenant_id = :t and aggregate_type = 'learning'
              and (payload::text like :n or (type = 'fact.learned' and payload->>'subjectType' = 'CUSTOMER' and payload->>'subjectId' in (:c) and jsonb_exists(payload, 'value')))
            """).param("t", t).param("n", "%" + Json.write(ref) + "%").param("c", names).query().listOfRows();
        int n = 0;
        for (Map<String, Object> row : rows) {
            Map<String, Object> payload = Json.readMap((String) row.get("payload"));
            Object replaced = replace(payload, ref, token);
            @SuppressWarnings("unchecked") Map<String, Object> out = new LinkedHashMap<>((Map<String, Object>) replaced);
            if ("fact.learned".equals(row.get("type")) && "CUSTOMER".equals(out.get("subjectType")) && out.containsKey("value")) {
                out.remove("value");
                out.put("valueOmitted", "customer fact: the value was erased (GV-06)");
            }
            String previous = out.get("previousChainDigest") == null ? null : String.valueOf(out.get("previousChainDigest"));
            out.remove("payloadDigest");
            out.remove("chainDigest");
            out.remove("previousChainDigest");
            out.put("erased", true);
            String digest = Json.sha256(out);
            out.put("payloadDigest", digest);
            if (previous != null) out.put("previousChainDigest", previous);
            out.put("chainDigest", previous == null ? digest : Json.sha256(Map.of("previous", previous, "payload", digest)));
            n += jdbc.sql("""
                with erasure as (select set_config('oms.legal_erasure', 'on', true) as on_)
                update event set payload = :p from erasure where tenant_id = :t and event_id = :id and erasure.on_ = 'on'
                """).param("p", ai.intentoms.platform.jdbc.Jsonb.of(out)).param("t", t).param("id", row.get("event_id")).update();
        }
        jdbc.sql("select set_config('oms.legal_erasure', '', true)").query(String.class).single();
        return n;
    }

    /** {@code v} with every string equal to {@code from} replaced by {@code to}, at any depth. */
    static Object replace(Object v, String from, String to) {
        if (v instanceof String s) return s.equals(from) ? to : s;
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, x) -> out.put(String.valueOf(k), replace(x, from, to)));
            return out;
        }
        if (v instanceof List<?> l) return l.stream().map(x -> replace(x, from, to)).toList();
        return v;
    }

    private ai.intentoms.learning.store.CustomerSubjects subjects;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCustomerSubjects(ai.intentoms.learning.store.CustomerSubjects subjects) { this.subjects = subjects; }

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
