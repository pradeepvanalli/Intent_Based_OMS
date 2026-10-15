package ai.intentoms.finance.compliance;

import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Tenant-scoped SQL for {@code compliance_rule}. */
@Repository
public class ComplianceRuleRepository {
    private final JdbcClient jdbc;

    static final RowMapper<ComplianceRule> MAPPER = (rs, i) -> new ComplianceRule(
        rs.getString("id"), rs.getString("name"), rs.getString("scope"), rs.getString("when_expr"), rs.getString("action"),
        rs.getString("reason_code"), rs.getString("severity"), rs.getString("message"), rs.getInt("priority"), rs.getBoolean("active"));

    public ComplianceRuleRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void upsert(ComplianceRule r) {
        jdbc.sql("""
            insert into compliance_rule(tenant_id, id, name, scope, when_expr, action, reason_code, severity, message, priority, active, updated_at)
            values (:t, :id, :name, :scope, :when, :action, :rc, :sev, :msg, :prio, :active, now())
            on conflict (tenant_id, id) do update set name = excluded.name, scope = excluded.scope, when_expr = excluded.when_expr,
              action = excluded.action, reason_code = excluded.reason_code, severity = excluded.severity, message = excluded.message,
              priority = excluded.priority, active = excluded.active, updated_at = now()
            """)
            .param("t", TenantContext.require()).param("id", r.id()).param("name", r.name()).param("scope", r.scope()).param("when", r.when())
            .param("action", r.action()).param("rc", r.reasonCode()).param("sev", r.severity()).param("msg", r.message())
            .param("prio", r.priority()).param("active", r.active()).update();
    }

    public List<ComplianceRule> all() {
        return jdbc.sql("select * from compliance_rule where tenant_id = :t order by priority, id").param("t", TenantContext.require()).query(MAPPER).list();
    }

    public List<ComplianceRule> active() {
        return jdbc.sql("select * from compliance_rule where tenant_id = :t and active order by priority, id").param("t", TenantContext.require()).query(MAPPER).list();
    }

    public int delete(String id) {
        return jdbc.sql("delete from compliance_rule where tenant_id = :t and id = :id").param("t", TenantContext.require()).param("id", id).update();
    }
}
