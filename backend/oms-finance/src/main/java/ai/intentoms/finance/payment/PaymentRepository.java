package ai.intentoms.finance.payment;

import ai.intentoms.finance.model.Money;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Hand-written SQL for the {@code payment} and {@code refund} tables; every statement is tenant-scoped. */
@Repository
public class PaymentRepository {
    private final JdbcClient jdbc;

    static final RowMapper<Payment> PAYMENT = (rs, i) -> {
        String ccy = rs.getString("currency");
        Timestamp exp = rs.getTimestamp("auth_expires_at");
        String mandate = rs.getString("agent_mandate");
        return new Payment(
            rs.getString("id"), rs.getString("order_id"), rs.getString("tender_type"), rs.getString("tender_token"),
            rs.getString("psp"), rs.getString("psp_ref"), PaymentStatus.valueOf(rs.getString("status")),
            new Money(rs.getBigDecimal("amount_authorized"), ccy), new Money(rs.getBigDecimal("amount_captured"), ccy),
            exp == null ? null : exp.toInstant(), mandate == null ? Map.of() : Json.readMap(mandate), rs.getString("failure_code"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    };

    static final RowMapper<Refund> REFUND = (rs, i) -> new Refund(
        rs.getString("id"), rs.getString("order_id"), rs.getString("payment_id"), rs.getString("return_id"),
        new Money(rs.getBigDecimal("amount"), rs.getString("currency")), rs.getString("reason"), RefundStatus.valueOf(rs.getString("status")),
        rs.getString("psp_ref"), rs.getString("failure_code"), rs.getString("requested_by"),
        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());

    public PaymentRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void insert(Payment p) {
        jdbc.sql("""
            insert into payment(id, tenant_id, order_id, tender_type, tender_token, psp, psp_ref, status, amount_authorized, amount_captured,
                                currency, auth_expires_at, agent_mandate, failure_code, created_at, updated_at)
            values (:id, :t, :oid, :tt, :tok, :psp, :ref, :st, :auth, :cap, :ccy, :exp, :mandate, :fc, :c, :u)
            """)
            .param("id", p.id()).param("t", TenantContext.require()).param("oid", p.orderId()).param("tt", p.tenderType()).param("tok", p.tenderToken())
            .param("psp", p.psp()).param("ref", p.pspRef()).param("st", p.status().name()).param("auth", p.amountAuthorized().amount())
            .param("cap", p.amountCaptured().amount()).param("ccy", p.currency()).param("exp", ts(p.authExpiresAt()))
            .param("mandate", p.agentMandate() == null || p.agentMandate().isEmpty() ? null : Jsonb.of(p.agentMandate()))
            .param("fc", p.failureCode()).param("c", ts(p.createdAt())).param("u", ts(p.updatedAt())).update();
    }

    public void update(Payment p) {
        jdbc.sql("""
            update payment set psp_ref = :ref, status = :st, amount_authorized = :auth, amount_captured = :cap, auth_expires_at = :exp,
                               failure_code = :fc, updated_at = :u
            where tenant_id = :t and id = :id
            """)
            .param("ref", p.pspRef()).param("st", p.status().name()).param("auth", p.amountAuthorized().amount()).param("cap", p.amountCaptured().amount())
            .param("exp", ts(p.authExpiresAt())).param("fc", p.failureCode()).param("u", ts(p.updatedAt()))
            .param("t", TenantContext.require()).param("id", p.id()).update();
    }

    public Optional<Payment> find(String id) {
        return jdbc.sql("select * from payment where tenant_id = :t and id = :id").param("t", TenantContext.require()).param("id", id).query(PAYMENT).optional();
    }

    public Payment get(String id) { return find(id).orElseThrow(() -> new NotFoundException("Payment " + id + " not found")); }

    /** Payment locked for update; used while a capture / refund mutates balances. */
    public Payment lock(String id) {
        return jdbc.sql("select * from payment where tenant_id = :t and id = :id for update").param("t", TenantContext.require()).param("id", id).query(PAYMENT).optional()
            .orElseThrow(() -> new NotFoundException("Payment " + id + " not found"));
    }

    public List<Payment> forOrder(String orderId) {
        return jdbc.sql("select * from payment where tenant_id = :t and order_id = :o order by created_at, id")
            .param("t", TenantContext.require()).param("o", orderId).query(PAYMENT).list();
    }

    public void insertRefund(Refund r) {
        jdbc.sql("""
            insert into refund(id, tenant_id, order_id, payment_id, return_id, amount, currency, reason, status, psp_ref, failure_code, requested_by, created_at, updated_at)
            values (:id, :t, :oid, :pid, :rid, :amt, :ccy, :reason, :st, :ref, :fc, :by, :c, :u)
            """)
            .param("id", r.id()).param("t", TenantContext.require()).param("oid", r.orderId()).param("pid", r.paymentId()).param("rid", r.returnId())
            .param("amt", r.amount().amount()).param("ccy", r.amount().currency()).param("reason", r.reason()).param("st", r.status().name())
            .param("ref", r.pspRef()).param("fc", r.failureCode()).param("by", r.requestedBy()).param("c", ts(r.createdAt())).param("u", ts(r.updatedAt())).update();
    }

    public void updateRefund(Refund r) {
        jdbc.sql("update refund set status = :st, psp_ref = :ref, failure_code = :fc, updated_at = :u where tenant_id = :t and id = :id")
            .param("st", r.status().name()).param("ref", r.pspRef()).param("fc", r.failureCode()).param("u", ts(r.updatedAt()))
            .param("t", TenantContext.require()).param("id", r.id()).update();
    }

    public Optional<Refund> findRefund(String id) {
        return jdbc.sql("select * from refund where tenant_id = :t and id = :id").param("t", TenantContext.require()).param("id", id).query(REFUND).optional();
    }

    public List<Refund> refundsForOrder(String orderId) {
        return jdbc.sql("select * from refund where tenant_id = :t and order_id = :o order by created_at, id")
            .param("t", TenantContext.require()).param("o", orderId).query(REFUND).list();
    }

    /** Sum of refunds already issued (or in flight) against a payment; failed refunds do not count. */
    public BigDecimal refundedAgainst(String paymentId) {
        return jdbc.sql("select coalesce(sum(amount), 0) from refund where tenant_id = :t and payment_id = :p and status in ('PENDING','SUCCEEDED')")
            .param("t", TenantContext.require()).param("p", paymentId).query(BigDecimal.class).single();
    }

    private static Timestamp ts(Instant i) { return i == null ? null : Timestamp.from(i); }
}
