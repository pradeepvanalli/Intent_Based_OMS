package ai.intentoms.monitoring.exception;

import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Persistence of the exception taxonomy ({@code exception_type}, FR-MON-03). */
@Repository
public class ExceptionTypeRepository {
    private static final RowMapper<ExceptionTypeDef> MAPPER = (rs, i) -> new ExceptionTypeDef(rs.getString("type"), rs.getString("severity"), rs.getString("queue"),
        rs.getString("playbook"), rs.getInt("sla_minutes"), rs.getString("description"));
    private final JdbcClient jdbc;

    public ExceptionTypeRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public List<ExceptionTypeDef> list() {
        return jdbc.sql("select type, severity, queue, playbook, sla_minutes, description from exception_type where tenant_id = :t order by type")
            .param("t", TenantContext.require()).query(MAPPER).list();
    }

    public Optional<ExceptionTypeDef> find(String type) {
        return jdbc.sql("select type, severity, queue, playbook, sla_minutes, description from exception_type where tenant_id = :t and type = :ty")
            .param("t", TenantContext.require()).param("ty", type).query(MAPPER).optional();
    }

    public ExceptionTypeDef upsert(ExceptionTypeDef d) {
        jdbc.sql("""
            insert into exception_type(tenant_id, type, severity, queue, playbook, sla_minutes, description) values (:t, :ty, :sev, :q, :pb, :sla, :d)
            on conflict (tenant_id, type) do update set severity = excluded.severity, queue = excluded.queue, playbook = excluded.playbook,
              sla_minutes = excluded.sla_minutes, description = excluded.description, updated_at = now()
            """).param("t", TenantContext.require()).param("ty", d.type()).param("sev", d.severity()).param("q", d.queue()).param("pb", d.playbook())
            .param("sla", d.slaMinutes()).param("d", d.description()).update();
        return d;
    }
}
