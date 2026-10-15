package ai.intentoms.orchestration.autonomy;

import java.math.BigDecimal;
import java.util.List;

/**
 * {@code tenant_config} keys read by the Agentic Control Tower (design principle 8: business users change these at
 * runtime). Defaults apply when a tenant has not set the key. {@code autonomy.enabled} ships OFF by default
 * (safety first); the demo tenant turns it on with the defaults documented alongside each key.
 */
public final class AutonomyConfigKeys {
    private AutonomyConfigKeys() {}

    /** Master switch for autonomous execution. Default false: guardrails pass but nothing acts until a tenant opts in. */
    public static final String ENABLED = "autonomy.enabled";
    /** Emergency stop, independent of {@link #ENABLED}: an ops-facing toggle meant to be flipped instantly without touching the rest of the config. Default false (not killed). */
    public static final String KILL_SWITCH = "autonomy.kill_switch";
    /** Maximum autonomous actions this tenant may execute per UTC day. Default 10. */
    public static final String MAX_ACTIONS_PER_DAY = "autonomy.max_actions_per_day";
    /** Maximum estimated USD impact of a single autonomous action. Default 100.00. */
    public static final String MAX_USD_IMPACT_PER_ACTION = "autonomy.max_usd_impact_per_action";
    /** Allow-list of {@link AutonomousActionType} codes the tenant permits autonomously; refund/cancel are never allowed regardless of this list. Default [re_source, re_promise, retry_payment, release_hold, notify_customer]. */
    public static final String ALLOWED_ACTIONS = "autonomy.allowed_actions";
    /** Optional confidence floor a tenant can raise above the decision's own ACT threshold; never lowers it. Unset by default. */
    public static final String MIN_CONFIDENCE_OVERRIDE = "autonomy.min_confidence_override";
    /** Minutes an open order may sit with no further progress before the scanner flags it stuck. Default 180. */
    public static final String STUCK_AFTER_MINUTES = "autonomy.stuck_after_minutes";
    /** SLA breach-risk score (0-4) at or above which an open order is a scan candidate. Default 3. */
    public static final String RISK_SCORE_THRESHOLD = "autonomy.risk_score_threshold";
    /** Minutes a margin-floor-approval task may sit open before the scanner flags its order. Default 120. */
    public static final String MARGIN_APPROVAL_STALE_MINUTES = "autonomy.margin_approval_stale_minutes";
    /** Maximum open orders / tasks / exceptions examined per tenant per scan. Default 200. */
    public static final String SCAN_BATCH = "autonomy.scan_batch";

    public static final boolean DEFAULT_ENABLED = false;
    public static final boolean DEFAULT_KILL_SWITCH = false;
    public static final int DEFAULT_MAX_ACTIONS_PER_DAY = 10;
    public static final BigDecimal DEFAULT_MAX_USD_IMPACT_PER_ACTION = new BigDecimal("100.00");
    public static final List<String> DEFAULT_ALLOWED_ACTIONS = List.of(
        AutonomousActionType.RE_SOURCE.code(), AutonomousActionType.RE_PROMISE.code(),
        AutonomousActionType.RETRY_PAYMENT.code(), AutonomousActionType.RELEASE_HOLD.code(), AutonomousActionType.NOTIFY_CUSTOMER.code());
    public static final int DEFAULT_STUCK_AFTER_MINUTES = 180;
    public static final int DEFAULT_RISK_SCORE_THRESHOLD = 3;
    public static final int DEFAULT_MARGIN_APPROVAL_STALE_MINUTES = 120;
    public static final int DEFAULT_SCAN_BATCH = 200;
}
