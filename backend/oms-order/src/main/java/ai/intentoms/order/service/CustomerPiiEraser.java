package ai.intentoms.order.service;

import ai.intentoms.platform.pii.PiiShreddedEvent;
import ai.intentoms.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * GV-06 right to erasure for orders: when one customer is erased ({@code Tokenizer.shredSubject}), their orders keep
 * their lines, money and status but lose every personal detail. The customer block keeps only tier and age-verified
 * flags under the pseudonym; addresses keep country, state and the first three characters of the postcode (enough for
 * regional reporting, not enough to find anyone); notes the customer wrote are blanked. Idempotent. The event ledger is
 * append-only and is not rewritten; its personal fields are covered by tokenisation and key rotation.
 */
@Component
public class CustomerPiiEraser {
    private static final Logger log = LoggerFactory.getLogger(CustomerPiiEraser.class);
    private final JdbcClient jdbc;

    public CustomerPiiEraser(JdbcClient jdbc) { this.jdbc = jdbc; }

    @EventListener
    public void onShred(PiiShreddedEvent e) {
        if (e.tenantWide() || e.pseudonym() == null) return;
        TenantContext.runAs(e.tenantId(), () -> erase(e.subjectRef(), e.pseudonym()));
    }

    /** Returns how many orders were anonymised. */
    public int erase(String customerRef, String pseudonym) {
        String t = TenantContext.require();
        jdbc.sql("""
            update order_address a set address = jsonb_build_object('type', a.address->'type', 'state', a.address->'state', 'country', a.address->'country',
                                                                   'zip', left(coalesce(a.address->>'zip', ''), 3), 'erased', true)
            from orders o where o.tenant_id = :t and o.customer_ref = :c and a.tenant_id = o.tenant_id and a.order_id = o.id
            """).param("t", t).param("c", customerRef).update();
        jdbc.sql("""
            update order_note n set text = '[erased]', author = null
            from orders o where o.tenant_id = :t and o.customer_ref = :c and n.tenant_id = o.tenant_id and n.order_id = o.id and n.source <> 'system'
            """).param("t", t).param("c", customerRef).update();
        int orders = jdbc.sql("""
            update orders set customer_ref = :p,
                   customer = jsonb_build_object('ref', :p, 'tier', customer->'tier', 'ageVerified', coalesce(customer->'ageVerified', 'false'::jsonb), 'erased', true),
                   search_text = concat_ws(' ', id, channel_order_id, :p), updated_at = now()
            where tenant_id = :t and customer_ref = :c
            """).param("t", t).param("c", customerRef).param("p", pseudonym).update();
        if (orders > 0) log.info("GV-06: erased customer details on {} order(s) for tenant {}", orders, t);
        return orders;
    }
}
