package ai.intentoms.finance.guard;

import ai.intentoms.finance.model.Money;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * Append-only record of refund and appeasement spend per tenant, actor and agent (GR-05). Rolling-window sums feed
 * {@link SpendGuard}. Rows are never updated or deleted (database trigger), so the ledger doubles as reconciliation
 * evidence for the finance controller.
 */
@Repository
public class SpendLedger {
    private final JdbcClient jdbc;

    public SpendLedger(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void append(SpendCategory category, Money amount, String actor, String agentId, String refId, Instant at) {
        jdbc.sql("""
            insert into spend_ledger(tenant_id, category, actor, agent_id, amount, currency, ref_id, at)
            values (:t, :c, :a, :ag, :amt, :cur, :ref, :at)
            """)
            .param("t", TenantContext.require()).param("c", category.code()).param("a", actor).param("ag", agentId)
            .param("amt", amount.amount()).param("cur", amount.currency()).param("ref", refId).param("at", Timestamp.from(at))
            .update();
    }

    /** Total spend in the category for the tenant since {@code since} (exclusive of nothing; inclusive window start). */
    public BigDecimal spentSince(SpendCategory category, Instant since) {
        return jdbc.sql("select coalesce(sum(amount), 0) from spend_ledger where tenant_id = :t and category = :c and at >= :s")
            .param("t", TenantContext.require()).param("c", category.code()).param("s", Timestamp.from(since))
            .query(BigDecimal.class).single();
    }

    public BigDecimal spentByActorSince(SpendCategory category, String actor, Instant since) {
        return jdbc.sql("select coalesce(sum(amount), 0) from spend_ledger where tenant_id = :t and category = :c and actor = :a and at >= :s")
            .param("t", TenantContext.require()).param("c", category.code()).param("a", actor).param("s", Timestamp.from(since))
            .query(BigDecimal.class).single();
    }

    public BigDecimal spentByAgentSince(SpendCategory category, String agentId, Instant since) {
        return jdbc.sql("select coalesce(sum(amount), 0) from spend_ledger where tenant_id = :t and category = :c and agent_id = :ag and at >= :s")
            .param("t", TenantContext.require()).param("c", category.code()).param("ag", agentId).param("s", Timestamp.from(since))
            .query(BigDecimal.class).single();
    }
}
