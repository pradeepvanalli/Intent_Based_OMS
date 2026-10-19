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
 * customer's name, email, phone and address. When that customer is erased, every record whose input mentions them is
 * pseudonymised: personal fields become {@code "[erased]"} and the customer reference becomes its pseudonym, wherever it
 * appears (subject id, correlation id, input). The tokenised {@code state} sent to providers holds no raw PII and is kept.
 * This is the one documented exception to decision records being append-only by convention.
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
        List<Map<String, Object>> rows = jdbc.sql("""
            select id, input::text as input from decision_record
            where tenant_id = :t and (input::text like :n or subject_id = :c or correlation_id = :c)
            """).param("t", t).param("n", needle).param("c", customerRef).query().listOfRows();
        for (Map<String, Object> r : rows) {
            Object input = r.get("input") == null ? null : Json.read((String) r.get("input"), Object.class);
            Object scrubbed = scrub(input, customerRef, pseudonym);
            jdbc.sql("""
                update decision_record set input = :input,
                       subject_id = case when subject_id = :c then :p else subject_id end,
                       correlation_id = case when correlation_id = :c then :p else correlation_id end
                where tenant_id = :t and id = :id
                """).param("input", scrubbed == null ? null : Jsonb.of(scrubbed)).param("c", customerRef).param("p", pseudonym)
                .param("t", t).param("id", r.get("id")).update();
        }
        if (!rows.isEmpty()) log.info("GV-06: pseudonymised {} decision record(s) for tenant {}", rows.size(), t);
        return rows.size();
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
