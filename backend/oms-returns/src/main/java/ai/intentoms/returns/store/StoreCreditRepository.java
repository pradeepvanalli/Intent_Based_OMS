package ai.intentoms.returns.store;

import ai.intentoms.finance.model.Money;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.returns.model.StoreCredit;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Persistence for store credit issued by the default {@link ai.intentoms.returns.service.StoreCreditIssuer}. */
@Repository
public class StoreCreditRepository {
    private final JdbcClient jdbc;

    public StoreCreditRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public StoreCredit insert(StoreCredit c) {
        jdbc.sql("insert into store_credit(id, tenant_id, customer_ref, amount, currency, rma_id, order_id, reason, issued_by, created_at) values (:id, :t, :c, :a, :cur, :rma, :o, :r, :by, :at)")
            .param("id", c.id()).param("t", TenantContext.require()).param("c", c.customerRef()).param("a", c.amount().amount()).param("cur", c.amount().currency())
            .param("rma", c.rmaId()).param("o", c.orderId()).param("r", c.reason()).param("by", c.issuedBy()).param("at", Timestamp.from(c.createdAt())).update();
        return c;
    }

    public List<StoreCredit> forRma(String rmaId) {
        return jdbc.sql("select * from store_credit where tenant_id = :t and rma_id = :rma order by created_at").param("t", TenantContext.require()).param("rma", rmaId)
            .query().listOfRows().stream().map(StoreCreditRepository::map).toList();
    }

    public List<StoreCredit> forCustomer(String customerRef) {
        return jdbc.sql("select * from store_credit where tenant_id = :t and customer_ref = :c order by created_at desc").param("t", TenantContext.require()).param("c", customerRef)
            .query().listOfRows().stream().map(StoreCreditRepository::map).toList();
    }

    private static StoreCredit map(Map<String, Object> r) {
        return new StoreCredit((String) r.get("id"), (String) r.get("customer_ref"), new Money((BigDecimal) r.get("amount"), (String) r.get("currency")), (String) r.get("rma_id"),
            (String) r.get("order_id"), (String) r.get("reason"), (String) r.get("issued_by"), ((Timestamp) r.get("created_at")).toInstant());
    }
}
