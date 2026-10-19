package ai.intentoms.learning.governance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * B-1: every learned family a live path can read, with its value domain and the decisions it affects, in one place.
 * A live-use proposal's range must sit inside the domain and its {@code maxStep} may cover at most half of it
 * ({@code POST /learning/live-parameters/proposals}); {@link ParameterResolver} never serves a learned number from
 * outside the domain and says when it clamped one; and the guardrail monitor watches the affected decisions once the
 * family is live (EX-05). Kinds:
 * <ul>
 *   <li>{@code NUMBER}: one number per subject, in {@code units} from {@code min} to {@code max};</li>
 *   <li>{@code SWITCH}: the family is either used or not (the bandit acting, a calibration curve); it takes no range;</li>
 *   <li>{@code TABLE}: several learned values of different kinds per subject; a range, if given, bounds each number.</li>
 * </ul>
 */
public final class LiveFamilies {
    private LiveFamilies() {}

    public enum Kind { NUMBER, SWITCH, TABLE }

    public record Domain(String family, Kind kind, Double min, Double max, String units, List<String> affectedDecisions) {
        public boolean numeric() { return kind == Kind.NUMBER; }
        /** The largest {@code maxStep} one promotion may allow: half the domain. */
        public Double maxStepLimit() { return numeric() ? (max - min) / 2 : null; }
        public String describe() {
            return switch (kind) {
                case NUMBER -> String.format(Locale.ROOT, "%s, from %s to %s", units, fmt(min), fmt(max));
                case SWITCH -> "an on/off switch (" + units + ")";
                case TABLE -> units;
            };
        }
        public Map<String, Object> view() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("family", family);
            m.put("kind", kind.name());
            m.put("min", min);
            m.put("max", max);
            m.put("maxStepLimit", maxStepLimit());
            m.put("units", units);
            m.put("description", describe());
            m.put("affectedDecisions", affectedDecisions);
            return m;
        }
    }

    private static final Map<String, Domain> ALL = new LinkedHashMap<>();

    private static void number(String family, double min, double max, String units, String... decisions) {
        ALL.put(family, new Domain(family, Kind.NUMBER, min, max, units, List.of(decisions)));
    }

    private static void other(String family, Kind kind, String units, String... decisions) {
        ALL.put(family, new Domain(family, kind, null, null, units, List.of(decisions)));
    }

    static {
        number("sourcing.node_reject_rate", 0, 1, "share of orders a node rejects", "sourcing_node_score");
        number("sourcing.node_on_time_rate", 0, 1, "share of orders a node ships on time", "sourcing_node_score");
        number("sourcing.reject_penalty", 0, 5, "sourcing score penalty after a rejection", "sourcing_node_score");
        number("sourcing.node_backlog_breach", 0, 1, "probability that a node's backlog breaks the promise", "sourcing_node_score", "sla_breach_risk");
        other("sourcing.policy_bandit", Kind.SWITCH, "the explorer may choose the sourcing policy on a small share of orders", "sourcing_policy_bandit", "sourcing_node_score");
        other("sourcing.aged_stock_tiebreak", Kind.SWITCH, "near-equal plans prefer the node with the oldest stock", "sourcing_node_score");
        number("promise.lane_transit_days", 0, 60, "days in transit on a carrier lane", "sla_breach_risk", "sourcing_node_score");
        number("promise.lane_calibration", -1, 1, "correction to a stated on-time probability", "sla_breach_risk", "sourcing_node_score");
        number("promise.buffer_days", 0, 30, "days of padding on the delivery promise", "sla_breach_risk");
        number("monitoring.warn_early_threshold", 0, 4, "SLA breach risk score (0 none, 4 certain)", "sla_breach_risk");
        number("cs.appeasement_level", 0, 1000, "goodwill credit in the order currency", "appeasement");
        number("inventory.safety_stock", 0, 1_000_000, "units of safety stock", "sourcing_node_score");
        number("inventory.reservation_ttl", 1, 10_080, "minutes a cart holds stock", "sourcing_node_score");
        number("inventory.inbound_reliability", 0, 1, "share of announced inbound stock that arrives", "sourcing_node_score");
        number("inventory.backorder_conversion", 0, 1, "share of backorders customers wait for", "backorder_next_action");
        other("customer.preferences", Kind.TABLE, "each customer's learned preferences (split tolerance, speed vs cost, pickup, channel)", "fulfilment_intent");
        number("fraud.address_reuse_count", 0, 10_000, "number of customers sharing an address", "payment_risk");
        number("fraud.signal_risk", 0, 1, "fraud rate of a signal level", "payment_risk");
        other("decision.calibration", Kind.SWITCH, "decisions use the learned confidence curve");
    }

    public static Optional<Domain> of(String family) { return Optional.ofNullable(ALL.get(family)); }

    public static List<Domain> all() { return List.copyOf(ALL.values()); }

    /** Plain-language problems with a proposed live-use range; empty when the range is acceptable. */
    public static List<String> validate(String family, Double min, Double max, Double maxStep) {
        List<String> problems = new ArrayList<>();
        Domain d = ALL.get(family);
        if (d == null) {
            problems.add(family + " is not a learned value any live path reads, so it has no domain to check a range against");
            return problems;
        }
        if (d.kind() == Kind.SWITCH) {
            if (min != null || max != null || maxStep != null) problems.add(family + " is " + d.describe() + "; leave min, max and max step empty");
            return problems;
        }
        if (min != null && max != null && min > max) problems.add("min " + fmt(min) + " is above max " + fmt(max));
        if (maxStep != null && maxStep <= 0) problems.add("max step must be above 0");
        if (d.kind() == Kind.TABLE) return problems;
        if (min != null && (min < d.min() || min > d.max())) problems.add(outside("min", min, d));
        if (max != null && (max < d.min() || max > d.max())) problems.add(outside("max", max, d));
        if (maxStep != null && maxStep > d.maxStepLimit()) {
            problems.add(String.format(Locale.ROOT, "max step %s is too large for %s: one promotion may move it at most %s (half of %s to %s)",
                fmt(maxStep), family, fmt(d.maxStepLimit()), fmt(d.min()), fmt(d.max())));
        }
        return problems;
    }

    /** Whether a learned number can be a value of the family (always true for unknown, switch and table families). */
    public static boolean inDomain(String family, double value) {
        Domain d = ALL.get(family);
        return d == null || !d.numeric() || (value >= d.min() && value <= d.max());
    }

    private static String outside(String which, double v, Domain d) {
        return String.format(Locale.ROOT, "%s %s is outside the values %s can take: %s", which, fmt(v), d.family(), d.describe());
    }

    static String fmt(double v) { return v == Math.rint(v) && Math.abs(v) < 1e15 ? String.valueOf((long) v) : String.valueOf(v); }
}
