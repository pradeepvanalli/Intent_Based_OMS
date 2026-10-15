package ai.intentoms.analytics.kpi;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The section 13 success metrics plus the three control-tower gauges, with labels, units, one-sentence definitions and
 * targets. "vs baseline" targets are derived from tenant config {@code kpi.baseline.<key>} (see {@link KpiMath#targetFromBaseline});
 * any target can be pinned by {@code kpi.target.<key>}.
 */
public final class KpiCatalog {
    private KpiCatalog() {}

    /** How the headline value and trend of a KPI are derived from its daily points. */
    public enum Kind {
        /** sum(numerator) / sum(denominator) over the range. */
        RATIO,
        /** exact median over the range for the headline; daily medians (count-weighted) for the trend. */
        MEDIAN,
        /** point-in-time count; trend from {@code kpi_daily} snapshots plus today's live value. */
        GAUGE
    }

    /**
     * @param fixedTarget  absolute target from section 13 (null when relative or not applicable)
     * @param baselinePct  relative target as a signed fraction applied to the tenant baseline (e.g. -0.15 for "-15% vs baseline"); null when absolute
     * @param whenEmpty    value reported when nothing was measured in the range
     */
    public record KpiDef(String key, String label, String unit, Kind kind, String definition, Double fixedTarget, Double baselinePct, String targetDirection, double whenEmpty) {
        public boolean lowerIsBetter() { return "lte".equals(targetDirection); }
    }

    public static final String TOUCHLESS_RATE = "touchless_rate";
    public static final String PROMISE_ACCURACY = "promise_accuracy";
    public static final String SPLIT_RATE = "split_rate";
    public static final String COST_TO_SERVE = "cost_to_serve";
    public static final String MARGIN_FLOOR_COMPLIANCE = "margin_floor_compliance";
    public static final String STORE_REJECT_RATE = "store_reject_rate";
    public static final String EXCEPTION_RESOLUTION_MEDIAN = "exception_resolution_median";
    public static final String RETURN_ABUSE_LOSS = "return_abuse_loss";
    public static final String WISMO_PER_100 = "wismo_per_100";
    public static final String POLICY_LEAD_TIME = "policy_lead_time";
    public static final String DECISION_ACCURACY = "decision_accuracy";
    public static final String CALIBRATION_ERROR = "calibration_error";
    public static final String OVERRIDE_RATE = "override_rate";
    public static final String AI_COST_PER_ORDER = "ai_cost_per_order";
    public static final String TOUCHLESS_TODAY = "touchless_today";
    public static final String OPEN_ORDERS = "open_orders";
    public static final String EXCEPTIONS_OPEN = "exceptions_open";

    public static final List<KpiDef> ALL = List.of(
        new KpiDef(TOUCHLESS_RATE, "Touchless order rate", "pct", Kind.RATIO,
            "Share of orders placed in the range that reached a terminal status with no completed human task and no overridden decision.", 0.90, null, "gte", 0.0),
        new KpiDef(PROMISE_ACCURACY, "Promise accuracy", "pct", Kind.RATIO,
            "Share of orders delivered in the range whose delivery date was on or before the promised date of their fulfilment groups.", 0.95, null, "gte", 0.0),
        new KpiDef(SPLIT_RATE, "Split-shipment rate", "pct", Kind.RATIO,
            "Share of orders placed in the range fulfilled from more than one fulfilment group (package).", null, -0.15, "lte", 0.0),
        new KpiDef(COST_TO_SERVE, "Cost to serve", "usd", Kind.RATIO,
            "Estimated shipping, labour and packaging cost of the fulfilment groups divided by the number of sourced orders.", null, -0.08, "lte", 0.0),
        new KpiDef(MARGIN_FLOOR_COMPLIANCE, "Margin-floor compliance", "pct", Kind.RATIO,
            "Share of orders sourced below the margin floor that carry a recorded approval task; 100% when no order was below the floor.", 1.0, null, "gte", 1.0),
        new KpiDef(STORE_REJECT_RATE, "Store reject rate", "pct", Kind.RATIO,
            "Store-rejected or timed-out releases divided by fulfilment groups assigned to store nodes in the range.", null, -0.30, "lte", 0.0),
        new KpiDef(EXCEPTION_RESOLUTION_MEDIAN, "Exception resolution (median)", "minutes", Kind.MEDIAN,
            "Median minutes from an exception being raised to it being resolved, for exceptions resolved in the range.", null, -0.50, "lte", 0.0),
        new KpiDef(RETURN_ABUSE_LOSS, "Return abuse loss per $1k", "usd", Kind.RATIO,
            "Refunds on returns judged high or severe abuse risk, per $1,000 refunded on returns in the range (0 when the returns module is absent).", null, -0.20, "lte", 0.0),
        new KpiDef(WISMO_PER_100, "WISMO contacts per 100 orders", "count", Kind.RATIO,
            "Where-is-my-order contacts (cs.wismo events and customer_request_intent decisions answering wismo) per 100 orders placed.", null, -0.30, "lte", 0.0),
        new KpiDef(POLICY_LEAD_TIME, "Policy change lead time (T0-T1)", "hours", Kind.MEDIAN,
            "Median hours from a T0/T1 decision draft being created to that version being published, for versions published in the range.", 24.0, null, "lte", 0.0),
        new KpiDef(DECISION_ACCURACY, "Decision accuracy (weighted)", "pct", Kind.RATIO,
            "Labelled live decisions judged correct divided by all labelled live decisions in the range; overrides count as wrong.", null, null, "gte", 0.0),
        new KpiDef(CALIBRATION_ERROR, "Calibration error (mean)", "ratio", Kind.RATIO,
            "Volume-weighted gap between stated confidence and observed accuracy over ten confidence bins of labelled live decisions.", 0.05, null, "lte", 0.0),
        new KpiDef(OVERRIDE_RATE, "Override rate", "pct", Kind.RATIO,
            "Autonomous (act band) live decisions later reversed by a person, divided by all autonomous decisions in the range.", 0.05, null, "lte", 0.0),
        new KpiDef(AI_COST_PER_ORDER, "AI cost per order", "usd", Kind.RATIO,
            "Provider spend recorded in ai_spend divided by orders captured in the range (GR-17).", null, null, "lte", 0.0),
        new KpiDef(TOUCHLESS_TODAY, "Touchless rate today", "pct", Kind.RATIO,
            "Touchless order rate restricted to orders placed today (UTC).", 0.90, null, "gte", 0.0),
        new KpiDef(OPEN_ORDERS, "Open orders", "count", Kind.GAUGE,
            "Orders whose header status is not terminal right now; the trend is the nightly end-of-day snapshot.", null, null, "lte", 0.0),
        new KpiDef(EXCEPTIONS_OPEN, "Exceptions open", "count", Kind.GAUGE,
            "Exceptions currently OPEN or IN_PROGRESS; the trend is the nightly end-of-day snapshot.", null, null, "lte", 0.0)
    );

    public static final Map<String, KpiDef> BY_KEY = ALL.stream().collect(Collectors.toUnmodifiableMap(KpiDef::key, Function.identity()));

    public static KpiDef get(String key) {
        KpiDef d = BY_KEY.get(key);
        if (d == null) throw new IllegalArgumentException("Unknown KPI " + key);
        return d;
    }

    /** Keys whose daily points are materialised by the roll-up (everything except the today-only tile). */
    public static List<String> rollupKeys() { return ALL.stream().map(KpiDef::key).filter(k -> !k.equals(TOUCHLESS_TODAY)).toList(); }
}
