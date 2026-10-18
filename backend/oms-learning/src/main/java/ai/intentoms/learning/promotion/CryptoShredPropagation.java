package ai.intentoms.learning.promotion;

import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GV-06: when a subject's PII key is crypto-shredded, learned facts and episodes tied to that subject must stop
 * being usable. {@code purgeSubject} does that for a CUSTOMER subject id (the only subject type crypto-shred
 * actually concerns — nodes/lanes/SKUs/agents/decisions aren't PII).
 *
 * <p><b>Integration point, stated honestly</b>: {@code ai.intentoms.platform.pii.Tokenizer.shred(tenant)} is a
 * tenant-wide key rotation (a CCPA deletion today rotates the whole tenant's PII key, not one customer's), and
 * nothing in the codebase currently fires a per-customer deletion event this class could subscribe to. Until
 * either exists, call {@link #purgeSubject} directly from wherever a future per-customer deletion lands, or extend
 * this to a tenant-wide sweep (delete every CUSTOMER-scoped fact/episode, matching {@code Tokenizer.shred}'s own
 * tenant-wide scope) if that's the deletion granularity this product actually ships. Left as two real, callable
 * methods rather than a fake event subscription to a trigger that doesn't exist.
 */
@Service
public class CryptoShredPropagation {
    private static final Logger log = LoggerFactory.getLogger(CryptoShredPropagation.class);
    private final JdbcClient jdbc;

    public CryptoShredPropagation(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Transactional
    public void purgeSubject(String customerId) {
        String t = TenantContext.require();
        int facts = jdbc.sql("delete from learned_fact where tenant_id = :t and subject_type = 'CUSTOMER' and subject_id = :c").param("t", t).param("c", customerId).update();
        // Episodes aren't keyed by customer id directly (only order id), so a customer-level purge of episode rows
        // needs the order-id set for that customer from oms-order, which this module deliberately doesn't depend
        // on — not attempted here rather than risking a wrong, overly-broad delete.
        log.info("GV-06: purged {} learned facts for customer {} (episode purge needs an order-id lookup this module doesn't have — see class javadoc)", facts, customerId);
    }

    /** Tenant-wide purge, matching {@code Tokenizer.shred}'s actual (tenant-level) deletion granularity. */
    @Transactional
    public void purgeTenantCustomerFacts() {
        String t = TenantContext.require();
        int n = jdbc.sql("delete from learned_fact where tenant_id = :t and subject_type = 'CUSTOMER'").param("t", t).update();
        log.info("GV-06: purged {} customer-scoped learned facts for tenant {} following a crypto-shred", n, t);
    }
}
