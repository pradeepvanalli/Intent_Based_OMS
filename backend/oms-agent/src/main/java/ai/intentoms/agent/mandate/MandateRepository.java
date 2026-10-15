package ai.intentoms.agent.mandate;

import ai.intentoms.finance.model.Money;
import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** JDBC access to {@code mandate}; tenant-scoped. */
@Repository
public class MandateRepository {
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
    private final JdbcClient jdbc;

    public MandateRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    private static final String SELECT = """
        select mandate_id, agent_id, buyer_ref, scope::text as scope, spend_limit, currency, instruction_text, signature, signature_valid, payment_token,
               injection_risk, injection_record_id, issued_at, expires_at, status, orders_used::text as orders_used, created_at, updated_at
        from mandate
        """;

    private static final RowMapper<Mandate> ROW = (rs, i) -> new Mandate(rs.getString("mandate_id"), rs.getString("agent_id"), rs.getString("buyer_ref"),
        Json.read(rs.getString("scope"), MandateScope.class), Money.of(rs.getBigDecimal("spend_limit"), rs.getString("currency")), rs.getString("instruction_text"),
        rs.getString("signature"), rs.getObject("signature_valid") == null ? null : rs.getBoolean("signature_valid"), rs.getString("payment_token"),
        rs.getObject("injection_risk") == null ? null : rs.getBigDecimal("injection_risk").doubleValue(), rs.getString("injection_record_id"),
        ts(rs.getTimestamp("issued_at")), ts(rs.getTimestamp("expires_at")), rs.getString("status"), Json.read(rs.getString("orders_used"), STRINGS),
        ts(rs.getTimestamp("created_at")), ts(rs.getTimestamp("updated_at")));

    private static Instant ts(Timestamp t) { return t == null ? null : t.toInstant(); }

    public void insert(Mandate m) {
        jdbc.sql("""
            insert into mandate(tenant_id, mandate_id, agent_id, buyer_ref, scope, spend_limit, currency, instruction_text, signature, signature_valid, payment_token,
                                injection_risk, injection_record_id, issued_at, expires_at, status, orders_used)
            values (:t, :id, :agent, :buyer, :scope, :limit, :ccy, :text, :sig, :sigValid, :token, :risk, :riskRec, :issued, :expires, :status, :used)
            """)
            .param("t", TenantContext.require()).param("id", m.id()).param("agent", m.agentId()).param("buyer", m.buyerRef()).param("scope", Jsonb.of(m.scope()))
            .param("limit", m.spendLimit().amount()).param("ccy", m.spendLimit().currency()).param("text", m.instructionText()).param("sig", m.signature())
            .param("sigValid", m.signatureValid()).param("token", m.paymentToken()).param("risk", m.injectionRisk()).param("riskRec", m.injectionRecordId())
            .param("issued", Timestamp.from(m.issuedAt())).param("expires", Timestamp.from(m.expiresAt())).param("status", m.status()).param("used", Jsonb.of(m.usedOrderIds()))
            .update();
    }

    public void updateStatus(String mandateId, String status, List<String> used) {
        jdbc.sql("update mandate set status = :s, orders_used = :used, updated_at = now() where tenant_id = :t and mandate_id = :id")
            .param("s", status).param("used", Jsonb.of(used)).param("t", TenantContext.require()).param("id", mandateId).update();
    }

    public Optional<Mandate> find(String mandateId) {
        return jdbc.sql(SELECT + " where tenant_id = :t and mandate_id = :id").param("t", TenantContext.require()).param("id", mandateId).query(ROW).optional();
    }

    public List<Mandate> forAgent(String agentId) {
        return jdbc.sql(SELECT + " where tenant_id = :t and agent_id = :a order by issued_at desc").param("t", TenantContext.require()).param("a", agentId).query(ROW).list();
    }

    public List<Mandate> list(int limit) {
        return jdbc.sql(SELECT + " where tenant_id = :t order by issued_at desc limit :n").param("t", TenantContext.require()).param("n", Math.max(1, Math.min(1000, limit))).query(ROW).list();
    }
}
