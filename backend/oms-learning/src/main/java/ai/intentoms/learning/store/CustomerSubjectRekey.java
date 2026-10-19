package ai.intentoms.learning.store;

import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NF-07 migration: learning rows written before customers were keyed by vault token ({@link CustomerSubjects}) are
 * re-keyed at startup, per tenant, through the tokenizer (a SQL migration cannot compute the tenant-keyed HMAC):
 * CUSTOMER learned facts, customer preference evidence (weights of the raw and token rows merged), order attribution,
 * episodes about a customer, and the sample members listed on segment definitions. Idempotent: once no raw reference
 * is left the job finds nothing. Ledger events are append-only and keep what they recorded.
 */
@Component
public class CustomerSubjectRekey {
    private static final Logger log = LoggerFactory.getLogger(CustomerSubjectRekey.class);
    static final String RAW = "!~ '^cu_[0-9a-f]{20}$'";

    private final JdbcClient jdbc;
    private final CustomerSubjects subjects;
    private final TransactionTemplate tx;
    private final LearnedFactCache cache;

    public CustomerSubjectRekey(JdbcClient jdbc, CustomerSubjects subjects, TransactionTemplate tx, LearnedFactCache cache) {
        this.jdbc = jdbc;
        this.subjects = subjects;
        this.tx = tx;
        this.cache = cache;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            List<String> tenants = jdbc.sql("""
                select tenant_id from learned_fact where subject_type = 'CUSTOMER' and subject_id %1$s
                union select tenant_id from customer_preference_evidence where subject_kind = 'customer' and subject_id %1$s
                union select tenant_id from customer_order_ref where customer_ref %1$s
                union select tenant_id from episode where subject_type = 'customer' and subject_id %1$s
                """.formatted(RAW)).query(String.class).list();
            for (String t : tenants) TenantContext.runAs(t, this::rekeyTenant);
        } catch (RuntimeException e) {
            log.error("NF-07: re-keying customer learning subjects failed: {}", e.toString());
        }
    }

    /** Re-keys the current tenant's raw customer references; returns how many distinct references were converted. */
    public int rekeyTenant() {
        String t = TenantContext.require();
        List<String> raw = jdbc.sql("""
            select subject_id from learned_fact where tenant_id = :t and subject_type = 'CUSTOMER' and subject_id %1$s
            union select subject_id from customer_preference_evidence where tenant_id = :t and subject_kind = 'customer' and subject_id %1$s
            union select customer_ref from customer_order_ref where tenant_id = :t and customer_ref %1$s
            union select subject_id from episode where tenant_id = :t and subject_type = 'customer' and subject_id %1$s
            """.formatted(RAW)).param("t", t).query(String.class).list();
        Map<String, String> tokens = new LinkedHashMap<>();
        for (String ref : raw) if (ref != null && !ref.isBlank()) tokens.put(ref, subjects.key(ref));
        for (Map.Entry<String, String> e : tokens.entrySet()) tx.executeWithoutResult(s -> rekey(t, e.getKey(), e.getValue()));
        int segments = rekeySegmentSamples(t, tokens);
        if (!tokens.isEmpty()) {
            cache.invalidateAll();
            log.info("NF-07: re-keyed {} customer reference(s) to vault tokens for tenant {} ({} segment definition(s) updated)", tokens.size(), t, segments);
        }
        return tokens.size();
    }

    private void rekey(String t, String ref, String token) {
        jdbc.sql("update learned_fact set subject_id = :tok where tenant_id = :t and subject_type = 'CUSTOMER' and subject_id = :ref")
            .param("tok", token).param("t", t).param("ref", ref).update();
        // Two current versions of one fact (one learned under each name): the newer one stays current.
        jdbc.sql("""
            update learned_fact f set valid_to = now() where f.tenant_id = :t and f.subject_type = 'CUSTOMER' and f.subject_id = :tok and f.valid_to is null
              and exists (select 1 from learned_fact g where g.tenant_id = f.tenant_id and g.subject_type = 'CUSTOMER' and g.subject_id = f.subject_id
                          and g.fact_key = f.fact_key and g.valid_to is null and (g.created_at, g.fact_id) > (f.created_at, f.fact_id))
            """).param("t", t).param("tok", token).update();
        jdbc.sql("""
            insert into customer_preference_evidence(tenant_id, subject_kind, subject_id, fact_key, option, weight, updated_at)
            select tenant_id, subject_kind, :tok, fact_key, option, weight, updated_at from customer_preference_evidence
            where tenant_id = :t and subject_kind = 'customer' and subject_id = :ref
            on conflict (tenant_id, subject_kind, subject_id, fact_key, option)
            do update set weight = customer_preference_evidence.weight + excluded.weight, updated_at = greatest(customer_preference_evidence.updated_at, excluded.updated_at)
            """).param("tok", token).param("t", t).param("ref", ref).update();
        jdbc.sql("delete from customer_preference_evidence where tenant_id = :t and subject_kind = 'customer' and subject_id = :ref").param("t", t).param("ref", ref).update();
        jdbc.sql("update customer_order_ref set customer_ref = :tok where tenant_id = :t and customer_ref = :ref").param("tok", token).param("t", t).param("ref", ref).update();
        jdbc.sql("update episode set subject_id = :tok where tenant_id = :t and subject_type = 'customer' and subject_id = :ref").param("tok", token).param("t", t).param("ref", ref).update();
    }

    @SuppressWarnings("unchecked")
    private int rekeySegmentSamples(String t, Map<String, String> tokens) {
        int n = 0;
        for (Map<String, Object> row : jdbc.sql("select segment_id, definition::text as d from segment where tenant_id = :t").param("t", t).query().listOfRows()) {
            Map<String, Object> def = Json.readMap((String) row.get("d"));
            if (!(def.get("sampleMembers") instanceof List<?> members)) continue;
            List<Object> keyed = new ArrayList<>();
            boolean changed = false;
            for (Object m : members) {
                String id = m == null ? null : String.valueOf(m);
                String tok = id == null || CustomerSubjects.isToken(id) ? id : tokens.getOrDefault(id, subjects.key(id));
                changed |= tok != null && !tok.equals(id);
                keyed.add(tok);
            }
            if (!changed) continue;
            Map<String, Object> out = new LinkedHashMap<>(def);
            out.put("sampleMembers", keyed);
            jdbc.sql("update segment set definition = :d where tenant_id = :t and segment_id = :id").param("d", Jsonb.of(out)).param("t", t).param("id", row.get("segment_id")).update();
            n++;
        }
        return n;
    }
}
