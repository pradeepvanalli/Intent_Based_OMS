package ai.intentoms.platform.api;

import ai.intentoms.platform.audit.AuditLog;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.ledger.Event;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.security.Permissions;
import ai.intentoms.platform.security.RequiresPermission;
import ai.intentoms.platform.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

import java.util.List;
import java.util.Map;

/** Tenant configuration, webhooks, outbox and ledger browsing. */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private final TenantConfigStore config;
    private final JdbcClient jdbc;
    private final EventStore events;
    private final AuditLog audit;
    private final AuthorizationService authz;

    public AdminController(TenantConfigStore config, JdbcClient jdbc, EventStore events, AuditLog audit, AuthorizationService authz) {
        this.authz = authz;
        this.config = config;
        this.jdbc = jdbc;
        this.events = events;
        this.audit = audit;
    }

    public record CreateTenantRequest(@jakarta.validation.constraints.NotBlank String tenantId, String name, AdminUser admin) {
        public record AdminUser(@jakarta.validation.constraints.NotBlank String userId, @jakarta.validation.constraints.NotBlank String password, java.util.Set<String> roles) {}
    }

    /**
     * Creates a tenant and (optionally) its first administrator. Only a platform administrator (a principal holding the
     * wildcard permission) may do this, because it crosses tenant boundaries; everything else in the API is tenant-scoped.
     */
    @PostMapping("/tenants")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createTenant(@Valid @RequestBody CreateTenantRequest req) {
        if (!authz.requirePrincipal().permissions().contains("*")) throw new ai.intentoms.platform.error.ForbiddenException("Creating tenants requires a platform administrator");
        String id = req.tenantId().trim().toLowerCase();
        if (!id.matches("[a-z0-9][a-z0-9_-]{1,62}")) throw new ai.intentoms.platform.error.ValidationException("tenantId must be 2-63 chars of a-z, 0-9, '-' or '_'");
        int created = jdbc.sql("insert into tenant(tenant_id, name) values (:id, :n) on conflict do nothing").param("id", id).param("n", req.name() == null || req.name().isBlank() ? id : req.name()).update();
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("tenantId", id);
        out.put("created", created == 1);
        if (req.admin() != null) {
            java.util.Set<String> roles = req.admin().roles() == null || req.admin().roles().isEmpty() ? java.util.Set.of("admin") : req.admin().roles();
            int n = jdbc.sql("insert into app_user(tenant_id, user_id, display_name, password_hash, roles) values (:t, :u, :u, :h, :r) on conflict (tenant_id, user_id) do nothing")
                .param("t", id).param("u", req.admin().userId()).param("h", new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(req.admin().password()))
                .param("r", Jsonb.of(List.copyOf(roles))).update();
            out.put("adminCreated", n == 1);
            out.put("adminUserId", req.admin().userId());
        }
        audit.record("tenant.created", "tenant", id, "created via admin API", Map.of("created", created == 1, "admin", req.admin() == null ? "" : req.admin().userId()));
        return out;
    }

    @GetMapping("/tenants")
    public List<Map<String, Object>> tenants() {
        if (!authz.requirePrincipal().permissions().contains("*")) throw new ai.intentoms.platform.error.ForbiddenException("Listing tenants requires a platform administrator");
        return jdbc.sql("select tenant_id, name, status, created_at from tenant order by created_at").query().listOfRows();
    }

    @GetMapping("/config")
    @RequiresPermission({Permissions.CONFIG_ADMIN, Permissions.DECISION_READ})
    public Map<String, Object> config(@RequestParam(defaultValue = "") String prefix) { return config.all(prefix); }

    public record ConfigWrite(Object value, String reason) {}

    @PutMapping("/config/{key}")
    @RequiresPermission(Permissions.CONFIG_ADMIN)
    public Map<String, Object> set(@PathVariable String key, @RequestBody ConfigWrite body) {
        // TenantConfigStore binds a String as raw JSON text (callers pre-encode), so a plain string value is encoded here.
        config.set(key, body.value() instanceof String s ? ai.intentoms.platform.json.Json.write(s) : body.value());
        audit.record("config.set", "tenant_config", key, body.reason(), Map.of("value", body.value()));
        return Map.of("key", key, "value", body.value());
    }

    @DeleteMapping("/config/{key}")
    @RequiresPermission(Permissions.CONFIG_ADMIN)
    public void delete(@PathVariable String key, @RequestParam(required = false) String reason) {
        config.delete(key);
        audit.record("config.delete", "tenant_config", key, reason, Map.of());
    }

    public record WebhookCreate(String url, List<String> eventFilter) {}

    @PostMapping("/webhooks")
    @RequiresPermission(Permissions.CONFIG_ADMIN)
    public Map<String, Object> createWebhook(@RequestBody WebhookCreate req) {
        String id = Ids.prefixed("wh");
        String secret = Ids.shortId() + Ids.shortId();
        jdbc.sql("insert into webhook_subscription(id, tenant_id, url, secret, event_filter) values (:id, :t, :u, :s, :f)")
            .param("id", id).param("t", TenantContext.require()).param("u", req.url()).param("s", secret)
            .param("f", Jsonb.of(req.eventFilter() == null ? List.of("*") : req.eventFilter())).update();
        return Map.of("id", id, "secret", secret);
    }

    @GetMapping("/webhooks")
    @RequiresPermission(Permissions.CONFIG_ADMIN)
    public List<Map<String, Object>> webhooks() {
        return jdbc.sql("select id, url, event_filter::text as event_filter, active, created_at from webhook_subscription where tenant_id = :t")
            .param("t", TenantContext.require()).query().listOfRows();
    }

    @GetMapping("/outbox")
    @RequiresPermission(Permissions.CONFIG_ADMIN)
    public Map<String, Object> outbox() {
        long pending = jdbc.sql("select count(*) from outbox where tenant_id = :t and published_at is null and dead_lettered_at is null").param("t", TenantContext.require()).query(Long.class).single();
        var failed = jdbc.sql("""
            select event_id, type, attempts, last_error, next_attempt_at, pending_subscribers::text as pending_subscribers from outbox
            where tenant_id = :t and published_at is null and dead_lettered_at is null and attempts > 0 order by position limit 50
            """).param("t", TenantContext.require()).query().listOfRows();
        var dead = jdbc.sql("""
            select event_id, type, attempts, last_error, dead_lettered_at, pending_subscribers::text as pending_subscribers from outbox
            where tenant_id = :t and published_at is null and dead_lettered_at is not null order by position desc limit 50
            """).param("t", TenantContext.require()).query().listOfRows();
        return Map.of("pending", pending, "failing", failed, "deadLettered", dead);
    }

    @GetMapping("/ledger/{aggregateType}/{aggregateId}")
    @RequiresPermission({Permissions.ORDER_READ, Permissions.CONFIG_ADMIN})
    public List<Event> ledger(@PathVariable String aggregateType, @PathVariable String aggregateId) { return events.load(aggregateType, aggregateId); }

    @GetMapping("/audit")
    @RequiresPermission({Permissions.CONFIG_ADMIN, Permissions.DECISION_READ})
    public List<Map<String, Object>> audit(@RequestParam(required = false) String objectType, @RequestParam(required = false) String objectId, @RequestParam(defaultValue = "100") int limit) {
        var q = jdbc.sql("select * from audit_log where tenant_id = :t" + (objectType != null ? " and object_type = :ot" : "") + (objectId != null ? " and object_id = :oid" : "") + " order by id desc limit :l")
            .param("t", TenantContext.require()).param("l", limit);
        if (objectType != null) q = q.param("ot", objectType);
        if (objectId != null) q = q.param("oid", objectId);
        return q.query().listOfRows();
    }
}
