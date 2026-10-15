package ai.intentoms.platform.config;

/** Well-known tenant_config keys. */
public final class ConfigKeys {
    private ConfigKeys() {}
    public static final String MARGIN_FLOOR = "sourcing.margin_floor";              // contribution margin ratio, e.g. 0.12
    public static final String MAX_SPLIT_PACKAGES = "sourcing.max_split_packages";
    public static final String SPLIT_PENALTY_USD = "sourcing.split_penalty_usd";
    public static final String CANDIDATE_LIMIT = "sourcing.candidate_limit";        // default 100
    public static final String CHANNEL_DEFAULT_POLICY = "sourcing.channel_default_policy"; // map channel -> policy
    public static final String AI_BUDGET_USD_PER_DAY = "ai.budget_usd_per_day";
    public static final String AI_KILL_SWITCH = "ai.kill_switch";                   // true disables all L1/L2
    public static final String DECISION_KILL_SWITCH_PREFIX = "decision.kill_switch."; // + decision name
    public static final String PROVIDER_KILL_SWITCH_PREFIX = "provider.kill_switch."; // + provider name
    public static final String REFUND_VELOCITY_CAP_USD_PER_HOUR = "guardrail.refund_velocity_usd_per_hour";
    public static final String APPEASEMENT_VELOCITY_CAP_USD_PER_HOUR = "guardrail.appeasement_velocity_usd_per_hour";
    public static final String RETENTION_YEARS = "retention.years";
    public static final String ALERT_SLACK_WEBHOOK = "alerts.slack_webhook";
    public static final String ALERT_PAGERDUTY_KEY = "alerts.pagerduty_routing_key";
    public static final String ALERT_EMAIL = "alerts.email";
}
