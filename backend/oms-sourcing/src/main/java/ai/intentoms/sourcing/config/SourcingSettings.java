package ai.intentoms.sourcing.config;

import ai.intentoms.platform.config.TenantConfigStore;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Typed view over the tenant's sourcing settings (defaults from {@link SourcingConfigKeys}). */
@Component
public class SourcingSettings {
    /** Composite plan weights (section 9 rule 1). */
    public record Weights(double cost, double time, double soft, double split) {
        public static Weights defaults() { return new Weights(SourcingConfigKeys.DEFAULT_W_COST, SourcingConfigKeys.DEFAULT_W_TIME, SourcingConfigKeys.DEFAULT_W_SOFT, SourcingConfigKeys.DEFAULT_W_SPLIT); }
        public Map<String, Object> asMap() { return Map.of("cost", cost, "time", time, "soft", soft, "split", split); }
    }

    private final TenantConfigStore config;

    public SourcingSettings(TenantConfigStore config) { this.config = config; }

    public double marginFloor() { return config.getDouble(SourcingConfigKeys.MARGIN_FLOOR, SourcingConfigKeys.DEFAULT_MARGIN_FLOOR); }
    public int maxSplitPackages() { return Math.max(1, config.getInt(SourcingConfigKeys.MAX_SPLIT_PACKAGES, SourcingConfigKeys.DEFAULT_MAX_SPLIT)); }
    public int candidateLimit() { return Math.max(1, config.getInt(SourcingConfigKeys.CANDIDATE_LIMIT, SourcingConfigKeys.DEFAULT_CANDIDATE_LIMIT)); }
    public boolean businessDaysOnly() { return config.getBoolean(SourcingConfigKeys.BUSINESS_DAYS_ONLY, false); }
    public double pickupRadiusKm() { return config.getDouble(SourcingConfigKeys.PICKUP_RADIUS_KM, SourcingConfigKeys.DEFAULT_PICKUP_RADIUS_KM); }
    public int backorderLeadDays() { return config.getInt(SourcingConfigKeys.BACKORDER_LEAD_DAYS, SourcingConfigKeys.DEFAULT_BACKORDER_LEAD_DAYS); }
    public double rejectPenalty() { return config.getDouble(SourcingConfigKeys.REJECT_PENALTY, SourcingConfigKeys.DEFAULT_REJECT_PENALTY); }

    /** Split penalty per extra package: tenant config, else {@code fallback} (the rate card's value), else 12. */
    public double splitPenaltyUsd(Double fallback) { return config.getDouble(SourcingConfigKeys.SPLIT_PENALTY_USD, fallback == null ? 12.0 : fallback); }

    public String channelDefaultPolicy(String channel) {
        Map<String, Object> m = config.getMap(SourcingConfigKeys.CHANNEL_DEFAULT_POLICY);
        Object p = channel == null ? null : m.get(channel);
        return p == null ? null : p.toString();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> orderTypeConstraints(String orderType) {
        Map<String, Object> m = config.getMap(SourcingConfigKeys.ORDER_TYPE_CONSTRAINTS);
        Object c = orderType == null ? null : m.get(orderType);
        return c instanceof Map<?, ?> cm ? (Map<String, Object>) cm : Map.of();
    }

    public Weights weights() {
        Map<String, Object> m = config.getMap(SourcingConfigKeys.WEIGHTS);
        if (m.isEmpty()) return Weights.defaults();
        return new Weights(num(m, "cost", SourcingConfigKeys.DEFAULT_W_COST), num(m, "time", SourcingConfigKeys.DEFAULT_W_TIME),
            num(m, "soft", SourcingConfigKeys.DEFAULT_W_SOFT), num(m, "split", SourcingConfigKeys.DEFAULT_W_SPLIT));
    }

    private static double num(Map<String, Object> m, String k, double dflt) { Object v = m.get(k); return v instanceof Number n ? n.doubleValue() : dflt; }
}
