package ai.intentoms.learning.store;

import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persistence for customer-intelligence evidence (CI-01, CI-03, CI-04): decayed evidence weights per customer and per
 * cohort segment, the order-to-customer map, and per-event idempotency. Decay: a weight touched after {@code d} days is
 * multiplied by {@code 0.5^(d / halfLifeDays)} before new evidence is added, and reads apply the same decay to "now".
 */
@Repository
public class CustomerEvidenceRepository {
    public static final String CUSTOMER = "customer";
    public static final String SEGMENT = "segment";

    private final JdbcClient jdbc;
    private ErasedSubjects erased;

    public CustomerEvidenceRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** GV-06 / N2: no evidence or order attribution is kept for an erased customer. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setErasedSubjects(ErasedSubjects erased) { this.erased = erased; }

    private boolean erased(String customerRef) { return erased != null && erased.erased(customerRef); }

    private static String t() { return TenantContext.require(); }

    /** False when this (event, fact key) was already used as evidence. */
    public boolean claimEvent(String eventId, String factKey) {
        return jdbc.sql("insert into customer_evidence_event(tenant_id, event_id, fact_key) values (:t, :e, :k) on conflict do nothing")
            .param("t", t()).param("e", eventId).param("k", factKey).update() == 1;
    }

    /** Decays every option of (subject, key) to {@code at}, then adds {@code amount} to {@code option}. */
    @Transactional
    public void add(String subjectKind, String subjectId, String factKey, String option, double amount, double halfLifeDays, Instant at) {
        if (CUSTOMER.equals(subjectKind) && erased(subjectId)) return;
        Timestamp ts = Timestamp.from(at);
        jdbc.sql("""
            update customer_preference_evidence
               set weight = weight * power(0.5, greatest(0, extract(epoch from (:at - updated_at))) / (86400.0 * :hl)), updated_at = :at
             where tenant_id = :t and subject_kind = :k and subject_id = :s and fact_key = :f and updated_at < :at
            """).param("at", ts).param("hl", halfLifeDays).param("t", t()).param("k", subjectKind).param("s", subjectId).param("f", factKey).update();
        jdbc.sql("""
            insert into customer_preference_evidence(tenant_id, subject_kind, subject_id, fact_key, option, weight, updated_at)
            values (:t, :k, :s, :f, :o, :w, :at)
            on conflict (tenant_id, subject_kind, subject_id, fact_key, option) do update set weight = customer_preference_evidence.weight + :w
            """).param("t", t()).param("k", subjectKind).param("s", subjectId).param("f", factKey).param("o", option).param("w", amount).param("at", ts).update();
    }

    /** Option weights of (subject, key), decayed to {@code at}. */
    public Map<String, Double> weights(String subjectKind, String subjectId, String factKey, double halfLifeDays, Instant at) {
        Map<String, Double> out = new LinkedHashMap<>();
        jdbc.sql("""
            select option, weight * power(0.5, greatest(0, extract(epoch from (:at - updated_at))) / (86400.0 * :hl)) as w
            from customer_preference_evidence where tenant_id = :t and subject_kind = :k and subject_id = :s and fact_key = :f order by option
            """).param("at", Timestamp.from(at)).param("hl", halfLifeDays).param("t", t()).param("k", subjectKind).param("s", subjectId).param("f", factKey)
            .query().listOfRows().forEach(r -> out.put((String) r.get("option"), ((Number) r.get("w")).doubleValue()));
        return out;
    }

    /** Customers whose newest evidence is older than {@code before} (their facts should drift back toward the prior). */
    public List<String> staleCustomers(Instant before, int limit) {
        return jdbc.sql("""
            select subject_id from customer_preference_evidence where tenant_id = :t and subject_kind = 'customer'
            group by subject_id having max(updated_at) < :before order by max(updated_at) limit :lim
            """).param("t", t()).param("before", Timestamp.from(before)).param("lim", limit).query(String.class).list();
    }

    /**
     * A8: customers holding evidence newer than their current fact for the same key (or no fact at all), e.g. evidence
     * that arrived while the learner was switched off, so the sweep can derive what was never derived.
     */
    public List<String> customersWithUnderivedEvidence(int limit) {
        return jdbc.sql("""
            select distinct e.subject_id from customer_preference_evidence e
            where e.tenant_id = :t and e.subject_kind = 'customer'
              and not exists (select 1 from learned_fact f where f.tenant_id = e.tenant_id and f.subject_type = 'CUSTOMER' and f.subject_id = e.subject_id
                              and f.fact_key = e.fact_key and f.valid_to is null and f.created_at >= e.updated_at)
            limit :lim
            """).param("t", t()).param("lim", limit).query(String.class).list();
    }

    public List<String> factKeysFor(String customerRef) {
        return jdbc.sql("select distinct fact_key from customer_preference_evidence where tenant_id = :t and subject_kind = 'customer' and subject_id = :s")
            .param("t", t()).param("s", customerRef).query(String.class).list();
    }

    // ---- orders

    public record OrderRef(String orderId, String customerRef, String cohort, int shipments, int maxPackages, boolean complained, String splitEvidence) {
        public boolean split() { return shipments > 1 || maxPackages > 1; }
    }

    public void registerOrder(String orderId, String customerRef, String cohort) {
        if (erased(customerRef)) return;
        jdbc.sql("""
            insert into customer_order_ref(tenant_id, order_id, customer_ref, cohort) values (:t, :o, :c, :k)
            on conflict (tenant_id, order_id) do update set customer_ref = excluded.customer_ref, cohort = excluded.cohort
            """).param("t", t()).param("o", orderId).param("c", customerRef).param("k", cohort).update();
    }

    public Optional<OrderRef> order(String orderId) {
        return jdbc.sql("select order_id, customer_ref, cohort, shipments, max_packages, complained, split_evidence from customer_order_ref where tenant_id = :t and order_id = :o")
            .param("t", t()).param("o", orderId)
            .query((rs, i) -> new OrderRef(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getBoolean(6), rs.getString(7))).optional();
    }

    public void recordShipment(String orderId, int packages) {
        jdbc.sql("update customer_order_ref set shipments = shipments + 1, max_packages = greatest(max_packages, :p) where tenant_id = :t and order_id = :o")
            .param("p", packages).param("t", t()).param("o", orderId).update();
    }

    public void recordComplaint(String orderId) {
        jdbc.sql("update customer_order_ref set complained = true where tenant_id = :t and order_id = :o").param("t", t()).param("o", orderId).update();
    }

    /** Claims the order's one piece of split evidence; false when it was already taken. */
    public boolean claimSplitEvidence(String orderId, String evidence) {
        return jdbc.sql("update customer_order_ref set split_evidence = :e where tenant_id = :t and order_id = :o and split_evidence is null")
            .param("e", evidence).param("t", t()).param("o", orderId).update() == 1;
    }

    /** Overrides earlier positive split evidence with a complaint that came after delivery. */
    public boolean flipSplitEvidenceToNegative(String orderId) {
        return jdbc.sql("update customer_order_ref set split_evidence = 'neg' where tenant_id = :t and order_id = :o and split_evidence = 'pos'")
            .param("t", t()).param("o", orderId).update() == 1;
    }

    /** Most recent cohort of a customer (their prior for CI-03), if they placed an order here. */
    public Optional<String> latestCohort(String customerRef) {
        return jdbc.sql("select cohort from customer_order_ref where tenant_id = :t and customer_ref = :c order by created_at desc limit 1")
            .param("t", t()).param("c", customerRef).query(String.class).optional();
    }

    public int orderCount(String customerRef) {
        return jdbc.sql("select count(*) from customer_order_ref where tenant_id = :t and customer_ref = :c").param("t", t()).param("c", customerRef).query(Integer.class).single();
    }

    /** Orders per customer, for every customer with at least one registered order. */
    public Map<String, Integer> orderCounts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        jdbc.sql("select customer_ref, count(*) as n from customer_order_ref where tenant_id = :t group by customer_ref")
            .param("t", t()).query().listOfRows().forEach(r -> out.put((String) r.get("customer_ref"), ((Number) r.get("n")).intValue()));
        return out;
    }

    private volatile Boolean ordersTable;

    /**
     * The order's customer straight from the orders table (decisions run before order.captured is delivered), else from
     * the order map. The orders table belongs to another module, so its presence is checked once rather than assumed
     * (a failed statement would abort the caller's transaction).
     */
    public Optional<String> customerOfOrder(String orderId) {
        if (ordersTable == null) ordersTable = jdbc.sql("select to_regclass('orders') is not null").query(Boolean.class).single();
        if (Boolean.TRUE.equals(ordersTable)) {
            Optional<String> fromOrders = jdbc.sql("select customer_ref from orders where tenant_id = :t and id = :o")
                .param("t", t()).param("o", orderId).query(String.class).optional().filter(c -> c != null && !c.isBlank());
            if (fromOrders.isPresent()) return fromOrders;
        }
        return order(orderId).map(OrderRef::customerRef);
    }
}
