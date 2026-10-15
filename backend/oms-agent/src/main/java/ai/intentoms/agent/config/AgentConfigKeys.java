package ai.intentoms.agent.config;

/** tenant_config keys read by the agent module. */
public final class AgentConfigKeys {
    private AgentConfigKeys() {}
    /** Probability at or above which agent-supplied text counts as injected (GR-08); default 0.7. */
    public static final String INJECTION_THRESHOLD = "agent.injection_threshold";
    /** Map agentId -> {secret, apiKey}; written by the loader only when {@code oms.agent.demo-expose-secrets=true}. */
    public static final String DEMO_SECRETS = "agents.demo_secrets";
    /** Default max order value when a registration gives none. */
    public static final String DEFAULT_MAX_ORDER_VALUE = "agent.default_max_order_value";
    /** Default max refund when a registration gives none. */
    public static final String DEFAULT_MAX_REFUND = "agent.default_max_refund";
}
