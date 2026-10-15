package ai.intentoms.platform.config;

import ai.intentoms.platform.jdbc.Jsonb;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runtime, per-tenant key/value configuration (design principle 8: change without deploys). Keys are dotted
 * ("sourcing.margin_floor", "decision.kill_switch.fulfilment_policy"). Values are JSON. Cached for 5 s so hot paths
 * (promise, sourcing) never hit the DB per request while changes still land within the control-tower freshness budget.
 */
@Component
public class TenantConfigStore {
    private final JdbcClient jdbc;
    private final Cache<String, Optional<Object>> cache = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(5)).maximumSize(50_000).build();

    public TenantConfigStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Optional<Object> get(String key) {
        String tenant = TenantContext.require();
        return cache.get(tenant + "|" + key, k -> jdbc.sql("select value::text from tenant_config where tenant_id = :t and key = :k")
            .param("t", tenant).param("k", key).query(String.class).optional().map(v -> Json.read(v, Object.class)));
    }

    public String getString(String key, String dflt) { return get(key).map(Object::toString).orElse(dflt); }
    public boolean getBoolean(String key, boolean dflt) { return get(key).map(v -> v instanceof Boolean b ? b : Boolean.parseBoolean(v.toString())).orElse(dflt); }
    public int getInt(String key, int dflt) { return get(key).map(v -> ((Number) (v instanceof Number n ? n : Double.parseDouble(v.toString()))).intValue()).orElse(dflt); }
    public double getDouble(String key, double dflt) { return get(key).map(v -> v instanceof Number n ? n.doubleValue() : Double.parseDouble(v.toString())).orElse(dflt); }
    public BigDecimal getDecimal(String key, BigDecimal dflt) { return get(key).map(v -> new BigDecimal(v.toString())).orElse(dflt); }
    @SuppressWarnings("unchecked")
    public Map<String, Object> getMap(String key) { return get(key).map(v -> (Map<String, Object>) v).orElse(Map.of()); }
    @SuppressWarnings("unchecked")
    public List<Object> getList(String key) { return get(key).map(v -> (List<Object>) v).orElse(List.of()); }

    public void set(String key, Object value) {
        String tenant = TenantContext.require();
        jdbc.sql("insert into tenant_config(tenant_id, key, value) values (:t, :k, :v) on conflict (tenant_id, key) do update set value = excluded.value, updated_at = now()")
            .param("t", tenant).param("k", key).param("v", Jsonb.of(value)).update();
        cache.invalidate(tenant + "|" + key);
    }

    public void delete(String key) {
        String tenant = TenantContext.require();
        jdbc.sql("delete from tenant_config where tenant_id = :t and key = :k").param("t", tenant).param("k", key).update();
        cache.invalidate(tenant + "|" + key);
    }

    public Map<String, Object> all(String prefix) {
        var rows = jdbc.sql("select key, value::text as value from tenant_config where tenant_id = :t and key like :p order by key")
            .param("t", TenantContext.require()).param("p", prefix + "%").query().listOfRows();
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        rows.forEach(r -> out.put((String) r.get("key"), Json.read((String) r.get("value"), Object.class)));
        return out;
    }

    /** Snapshot of the tenant map exposed to CEL guards as the "tenant" root (margin_floor etc.). */
    public Map<String, Object> celTenantMap() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", TenantContext.require());
        m.put("margin_floor", getDouble("sourcing.margin_floor", 0.0));
        m.put("max_split_packages", getInt("sourcing.max_split_packages", 3));
        all("").forEach((k, v) -> m.put(k.replace('.', '_'), v));
        return m;
    }
}
