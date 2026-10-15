package ai.intentoms.platform.audit;

import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Append-only who-what-when store for governance actions (FR-XAI-02, FR-PRM-06, GR-13). Rows are never updated or
 * deleted by the application; the table has a trigger that rejects UPDATE/DELETE.
 */
@Component
public class AuditLog {
    private final JdbcClient jdbc;
    private final AuthorizationService authz;

    public AuditLog(JdbcClient jdbc, AuthorizationService authz) {
        this.jdbc = jdbc;
        this.authz = authz;
    }

    public void record(String action, String objectType, String objectId, String reason, Map<String, Object> details) {
        jdbc.sql("insert into audit_log(tenant_id, actor, action, object_type, object_id, reason, details) values (:t, :a, :act, :ot, :oid, :r, :d)")
            .param("t", TenantContext.require()).param("a", authz.actorRef()).param("act", action).param("ot", objectType)
            .param("oid", objectId).param("r", reason).param("d", Jsonb.of(details == null ? Map.of() : details)).update();
    }
}
