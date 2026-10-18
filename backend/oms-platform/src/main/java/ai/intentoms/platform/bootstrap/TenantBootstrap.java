package ai.intentoms.platform.bootstrap;

import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.security.SecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Creates dev/bootstrap tenants and users from oms.security.dev-users ("id:password:tenant:role1|role2").
 * Runs before seeders in other modules (which use @Order(100+)).
 */
@Component
@Order(1)
public class TenantBootstrap {
    private static final Logger log = LoggerFactory.getLogger(TenantBootstrap.class);
    private final JdbcClient jdbc;
    private final SecurityProperties props;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public TenantBootstrap(JdbcClient jdbc, SecurityProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(1) // Spring 6.2 reads listener order from the method, not the class
    public void bootstrap() {
        for (String spec : props.devUsers()) {
            String[] p = spec.split(":");
            if (p.length < 4) { log.warn("Ignoring malformed dev user spec '{}'", spec); continue; }
            ensureTenant(p[2], p[2]);
            List<String> roles = Arrays.asList(p[3].split("\\|"));
            jdbc.sql("""
                insert into app_user(tenant_id, user_id, display_name, password_hash, roles) values (:t, :u, :u, :h, :r)
                on conflict (tenant_id, user_id) do update set roles = excluded.roles
                """).param("t", p[2]).param("u", p[0]).param("h", encoder.encode(p[1])).param("r", Jsonb.of(roles)).update();
        }
    }

    /** Creates the tenant row when absent; an existing row whose name is still the placeholder id gets {@code name}. */
    public void ensureTenant(String id, String name) {
        String n = name == null || name.isBlank() ? id : name;
        int created = jdbc.sql("insert into tenant(tenant_id, name) values (:id, :n) on conflict do nothing").param("id", id).param("n", n).update();
        if (created == 0 && !n.equals(id)) jdbc.sql("update tenant set name = :n where tenant_id = :id and name = :id").param("id", id).param("n", n).update();
    }

    /**
     * Creates a local user in the same store {@code POST /api/v1/admin/tenants} and the dev-user bootstrap use
     * ({@code app_user}, bcrypt). An existing user is left untouched (password and roles). Returns whether a row was created.
     * Dev/demo only: callers must not use it to create production users (use {@code POST /api/v1/auth/users}).
     */
    public boolean ensureUser(String tenantId, String userId, String password, List<String> roles) {
        int n = jdbc.sql("""
            insert into app_user(tenant_id, user_id, display_name, password_hash, roles) values (:t, :u, :u, :h, :r)
            on conflict (tenant_id, user_id) do nothing
            """).param("t", tenantId).param("u", userId).param("h", encoder.encode(password)).param("r", Jsonb.of(roles)).update();
        return n == 1;
    }
}
