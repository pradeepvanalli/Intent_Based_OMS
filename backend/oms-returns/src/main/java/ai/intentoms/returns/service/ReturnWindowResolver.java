package ai.intentoms.returns.service;

import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.json.Json;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.returns.config.ReturnsConfigKeys;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Return window per order type (FR-RET-02, FR-CAP-03): {@code returnWindowDays} from the order type definition stored
 * by oms-order in table {@code order_type} (when that table exists in this deployment), else the tenant override
 * {@code returns.window_days.<orderType>}, else {@code returns.window_days}, else 30.
 */
@Component
public class ReturnWindowResolver {
    private final JdbcClient jdbc;
    private final TenantConfigStore config;

    public ReturnWindowResolver(JdbcClient jdbc, TenantConfigStore config) {
        this.jdbc = jdbc;
        this.config = config;
    }

    public int windowDays(String orderType) {
        if (orderType != null) {
            Optional<Integer> fromTable = fromOrderTypeTable(orderType);
            if (fromTable.isPresent()) return fromTable.get();
            Optional<Object> override = config.get(ReturnsConfigKeys.WINDOW_DAYS_PREFIX + orderType);
            if (override.isPresent()) return config.getInt(ReturnsConfigKeys.WINDOW_DAYS_PREFIX + orderType, ReturnsConfigKeys.DEFAULT_WINDOW_DAYS);
        }
        return config.getInt(ReturnsConfigKeys.WINDOW_DAYS, ReturnsConfigKeys.DEFAULT_WINDOW_DAYS);
    }

    private Optional<Integer> fromOrderTypeTable(String orderType) {
        Boolean exists = jdbc.sql("select to_regclass('order_type') is not null").query(Boolean.class).single();
        if (!Boolean.TRUE.equals(exists)) return Optional.empty();
        Optional<String> def = jdbc.sql("select definition::text from order_type where tenant_id = :t and name = :n")
            .param("t", TenantContext.require()).param("n", orderType).query(String.class).optional();
        if (def.isEmpty()) return Optional.empty();
        Map<String, Object> m = Json.readMap(def.get());
        Object days = m.get("returnWindowDays");
        if (days == null) days = m.get("return_window_days");
        return days instanceof Number n ? Optional.of(n.intValue()) : Optional.empty();
    }
}
