package ai.intentoms.decision.record;

import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.pii.PiiShreddedEvent;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GV-06 for decision records: a record's {@code input} is kept in full for replay (FR-SIM-01) and so can hold a
 * customer's name, email, phone and address. When that customer is erased, every record that mentions them is
 * pseudonymised: personal fields become {@code "[erased]"} and the customer reference becomes its pseudonym, wherever it
 * appears (subject id, correlation id, input, details). What learning knew about the customer is removed from the
 * record too (C-N4): the {@code details.learnedFacts} entries about the customer (named by the raw reference or by its
 * vault token, which is the pseudonym) are dropped, so the Why view shows no learned fact about an erased person.
 * The tokenised {@code state} sent to providers holds no raw PII and is kept.
 *
 * <p>This is the one exception to decision records being append-only (§9.1, migration V405): the update runs with
 * {@code oms.legal_erasure} set for its own transaction, which the trigger honours for the personal columns only.
 */
@Component
public class DecisionInputErasure {
    private static final Logger log = LoggerFactory.getLogger(DecisionInputErasure.class);
    static final String ERASED = "[erased]";
    /** Keys (lower case, without '_' or '-') whose values are personal data wherever they appear in a decision input. */
    static final Set<String> PII_KEYS = Set.of("name", "fullname", "firstname", "lastname", "email", "phone", "mobile", "line1", "line2", "street", "address1", "address2",
        "city", "zip", "postalcode", "postcode", "lat", "lon", "latitude", "longitude", "instructiontext", "notes", "note", "giftmessage");

    private final JdbcClient jdbc;

    public DecisionInputErasure(JdbcClient jdbc) { this.jdbc = jdbc; }

    @EventListener
    public void onShred(PiiShreddedEvent e) {
        if (e.tenantWide() || e.pseudonym() == null) return;
        TenantContext.runAs(e.tenantId(), () -> erase(e.subjectRef(), e.pseudonym()));
    }

    /** Returns how many records were pseudonymised. */
    public int erase(String customerRef, String pseudonym) {
        String t = TenantContext.require();
        String needle = "%" + Json.write(customerRef) + "%";
        String tokenNeedle = "%" + Json.write(pseudonym) + "%";
        List<Map<String, Object>> rows = jdbc.sql("""
            select id, input::text as input, details::text as details from decision_record
            where tenant_id = :t and (input::text like :n or subject_id = :c or correlation_id = :c or details::text like :n or details::text like :tn)
            """).param("t", t).param("n", needle).param("tn", tokenNeedle).param("c", customerRef).query().listOfRows();
        for (Map<String, Object> r : rows) {
            Object input = r.get("input") == null ? null : Json.read((String) r.get("input"), Object.class);
            Object scrubbed = scrub(input, customerRef, pseudonym);
            Object details = r.get("details") == null ? null : scrub(dropCustomerFacts(Json.read((String) r.get("details"), Object.class), customerRef, pseudonym), customerRef, pseudonym);
            jdbc.sql("""
                with erasure as (select set_config('oms.legal_erasure', 'on', true) as on_)
                update decision_record set input = :input, details = coalesce(:details, details),
                       subject_id = case when subject_id = :c then :p else subject_id end,
                       correlation_id = case when correlation_id = :c then :p else correlation_id end
                from erasure where tenant_id = :t and id = :id and erasure.on_ = 'on'
                """).param("input", scrubbed == null ? null : Jsonb.of(scrubbed)).param("details", details == null ? null : Jsonb.of(details))
                .param("c", customerRef).param("p", pseudonym).param("t", t).param("id", r.get("id")).update();
        }
        jdbc.sql("select set_config('oms.legal_erasure', '', true)").query(String.class).single(); // scoped to the erasure statements
        if (!rows.isEmpty()) log.info("GV-06: pseudonymised {} decision record(s) for tenant {}", rows.size(), t);
        return rows.size();
    }

    /**
     * C-N4: the {@code learnedFacts} list without the entries about this customer (CUSTOMER facts named by the raw
     * reference or by its vault token). Other facts (a node's reliability, a lane's transit) stay.
     */
    static Object dropCustomerFacts(Object details, String ref, String pseudonym) {
        if (!(details instanceof Map<?, ?> m) || !(m.get("learnedFacts") instanceof List<?> facts)) return details;
        List<Object> kept = new ArrayList<>();
        for (Object f : facts) {
            if (f instanceof Map<?, ?> fm && "CUSTOMER".equalsIgnoreCase(String.valueOf(fm.get("subjectType")))
                && (String.valueOf(fm.get("subjectId")).equals(ref) || String.valueOf(fm.get("subjectId")).equals(pseudonym))) continue;
            kept.add(f);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, val) -> out.put(String.valueOf(k), val));
        out.put("learnedFacts", kept);
        if (kept.size() < facts.size()) out.put("learnedFactsErased", facts.size() - kept.size());
        return out;
    }

    @SuppressWarnings("unchecked")
    static Object scrub(Object v, String ref, String pseudonym) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, val) -> {
                String key = String.valueOf(k);
                if (PII_KEYS.contains(key.toLowerCase(java.util.Locale.ROOT).replace("_", "").replace("-", "")) && !(val instanceof Map) && !(val instanceof List)) {
                    out.put(key, val == null ? null : ERASED);
                } else out.put(key, scrub(val, ref, pseudonym));
            });
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object o : l) out.add(scrub(o, ref, pseudonym));
            return out;
        }
        if (v instanceof String s && s.equals(ref)) return pseudonym;
        return v;
    }
}
