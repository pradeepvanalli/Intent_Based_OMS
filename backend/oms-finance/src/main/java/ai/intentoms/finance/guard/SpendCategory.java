package ai.intentoms.finance.guard;

import ai.intentoms.finance.config.FinanceConfigKeys;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Spend categories governed by GR-05 velocity caps. Each category maps to its tenant / per-actor / per-agent cap keys
 * in {@code tenant_config} and carries conservative defaults used when a tenant has not configured a cap.
 */
public enum SpendCategory {
    REFUND(FinanceConfigKeys.REFUND_CAP_TENANT, FinanceConfigKeys.REFUND_CAP_PER_ACTOR, FinanceConfigKeys.REFUND_CAP_PER_AGENT,
        new BigDecimal("10000"), new BigDecimal("2500"), new BigDecimal("1000")),
    APPEASEMENT(FinanceConfigKeys.APPEASEMENT_CAP_TENANT, FinanceConfigKeys.APPEASEMENT_CAP_PER_ACTOR, FinanceConfigKeys.APPEASEMENT_CAP_PER_AGENT,
        new BigDecimal("2000"), new BigDecimal("500"), new BigDecimal("250"));

    private final String tenantCapKey;
    private final String actorCapKey;
    private final String agentCapKey;
    private final BigDecimal defaultTenantCap;
    private final BigDecimal defaultActorCap;
    private final BigDecimal defaultAgentCap;

    SpendCategory(String tenantCapKey, String actorCapKey, String agentCapKey, BigDecimal defaultTenantCap, BigDecimal defaultActorCap, BigDecimal defaultAgentCap) {
        this.tenantCapKey = tenantCapKey;
        this.actorCapKey = actorCapKey;
        this.agentCapKey = agentCapKey;
        this.defaultTenantCap = defaultTenantCap;
        this.defaultActorCap = defaultActorCap;
        this.defaultAgentCap = defaultAgentCap;
    }

    public String tenantCapKey() { return tenantCapKey; }
    public String actorCapKey() { return actorCapKey; }
    public String agentCapKey() { return agentCapKey; }
    public BigDecimal defaultTenantCap() { return defaultTenantCap; }
    public BigDecimal defaultActorCap() { return defaultActorCap; }
    public BigDecimal defaultAgentCap() { return defaultAgentCap; }

    /** Persisted form ("refund", "appeasement"). */
    public String code() { return name().toLowerCase(Locale.ROOT); }
}
