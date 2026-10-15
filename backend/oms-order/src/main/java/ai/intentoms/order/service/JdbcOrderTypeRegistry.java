package ai.intentoms.order.service;

import ai.intentoms.order.model.OrderType;
import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link OrderTypeRegistry} over table {@code order_type} (tenant_id, name, definition jsonb) with a 5 s per-tenant cache,
 * matching the tenant-config freshness budget: a type edited in the admin UI is live for the next capture without a deploy.
 */
@Service
public class JdbcOrderTypeRegistry implements OrderTypeRegistry {
    private final JdbcClient jdbc;
    private final Cache<String, Map<String, OrderType>> cache = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(5)).maximumSize(1_000).build();

    public JdbcOrderTypeRegistry(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public OrderType get(String name) {
        OrderType t = name == null ? null : types().get(name);
        if (t == null) throw new NotFoundException("Order type '" + name + "' is not configured", Map.of("orderType", String.valueOf(name)));
        return t;
    }

    @Override
    public List<OrderType> all() { return List.copyOf(types().values()); }

    @Override
    public OrderType upsert(OrderType type) {
        validate(type);
        String tenant = TenantContext.require();
        jdbc.sql("insert into order_type(tenant_id, name, definition) values (:t, :n, :d) on conflict (tenant_id, name) do update set definition = excluded.definition, updated_at = now()")
            .param("t", tenant).param("n", type.name()).param("d", Jsonb.of(type)).update();
        cache.invalidate(tenant);
        return type;
    }

    private Map<String, OrderType> types() {
        return cache.get(TenantContext.require(), tenant -> {
            Map<String, OrderType> out = new LinkedHashMap<>();
            jdbc.sql("select definition::text as d from order_type where tenant_id = :t order by name").param("t", tenant).query(String.class).list()
                .forEach(d -> { OrderType t = Json.read(d, OrderType.class); out.put(t.name(), t); });
            return out;
        });
    }

    static void validate(OrderType t) {
        if (t.name() == null || t.name().isBlank()) throw new ValidationException("order type name is required");
        if (t.headerStateModel() == null || t.headerStateModel().isBlank()) throw new ValidationException("order type " + t.name() + " needs headerStateModel");
        if (t.lineStateModel() == null || t.lineStateModel().isBlank()) throw new ValidationException("order type " + t.name() + " needs lineStateModel");
        if (t.workflowType() == null || t.workflowType().isBlank()) throw new ValidationException("order type " + t.name() + " needs workflowType");
    }
}
