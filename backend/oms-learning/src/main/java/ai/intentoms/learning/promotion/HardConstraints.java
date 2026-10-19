package ai.intentoms.learning.promotion;

import ai.intentoms.learning.governance.FeatureAllowlist;
import ai.intentoms.learning.model.ObjectiveProfile;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.PolicyChangeset;
import ai.intentoms.learning.store.ObjectiveProfileRepository;
import ai.intentoms.platform.config.TenantConfigStore;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Requirements §7 hard constraints, checked before a proposal is evaluated: a change that would breach one is refused
 * outright, never traded off against reward.
 * <ul>
 *   <li>HC-1 margin floor: no change may lower the margin floor below the tenant's floor (tenant config
 *       {@code sourcing.margin_floor} or the approved objective's {@code constraints.marginFloorPct}, the higher).</li>
 *   <li>HC-2 compliance holds: no change may retire, release or remove a compliance hold or screening rule.</li>
 *   <li>HC-3 refunds, cancellations and pricing are never autonomous: adding one to an autonomous-action list is refused.</li>
 *   <li>HC-4 daily action and dollar caps are set by people: no change may raise them.</li>
 *   <li>HC-5 node capacity and carrier cut-offs are physical limits: no change may move them.</li>
 *   <li>HC-6 no protected attributes: no rule may read one (postal-code scopes are allowed, with a reason code at
 *       approval).</li>
 * </ul>
 */
@Component
public class HardConstraints {
    private static final Pattern COMPLIANCE = Pattern.compile("compliance|sanction|export_control|age_check|age_verification|kyc|aml|embargo|hazmat|screening|_hold\\b|\\bhold_", Pattern.CASE_INSENSITIVE);
    private static final Pattern CAPS = Pattern.compile("(autonomy|action|refund|appeasement|order_value|spend).*(cap|limit|max|per_day|budget)|max_(refund|order_value|actions)|_usd_per_day", Pattern.CASE_INSENSITIVE);
    private static final Pattern PHYSICAL = Pattern.compile("capacity|cut_?off|max_units|staffing|throughput|dock", Pattern.CASE_INSENSITIVE);

    private final TenantConfigStore config;
    private final ObjectiveProfileRepository objectives;

    public HardConstraints(TenantConfigStore config, ObjectiveProfileRepository objectives) {
        this.config = config;
        this.objectives = objectives;
    }

    public record Violation(String code, String reason) {}

    public Optional<Violation> check(PolicyChangeset cs) {
        String refused = PromotionGuard.refusedOutright(cs);
        if (refused != null) return Optional.of(new Violation("HC-3", refused));
        String target = cs.target() == null ? "" : cs.target().name();
        for (PolicyChange c : cs.changes()) {
            Optional<Violation> v = switch (c) {
                case PolicyChange.ParameterChange pc -> numericKey(pc.parameter(), pc.from(), pc.to());
                case PolicyChange.WeightChange wc -> numericKey(wc.target() + "." + wc.weightKey(), wc.from(), wc.to());
                case PolicyChange.RuleRetire rr -> COMPLIANCE.matcher(rr.ruleId() + " " + nz(rr.reason())).find()
                    ? Optional.of(new Violation("HC-2", "rule '" + rr.ruleId() + "' is a compliance or screening rule and cannot be retired by learning")) : Optional.empty();
                case PolicyChange.RuleAdd ra -> ruleAdd(ra);
                case PolicyChange.AllowListChange a -> !a.add() && COMPLIANCE.matcher(a.listName() + " " + a.entry()).find()
                    ? Optional.of(new Violation("HC-2", "'" + a.entry() + "' cannot be removed from " + a.listName() + ": it is a compliance control")) : Optional.empty();
                case PolicyChange.ThresholdChange tc -> Optional.empty();
            };
            if (v.isPresent()) return v;
        }
        if (target.toLowerCase(Locale.ROOT).contains("margin_floor")) {
            for (PolicyChange c : cs.changes()) {
                if (c instanceof PolicyChange.ParameterChange pc) {
                    Optional<Violation> v = marginFloor(pc.to());
                    if (v.isPresent()) return v;
                }
            }
        }
        return Optional.empty();
    }

    private Optional<Violation> numericKey(String key, Object from, Object to) {
        String k = key == null ? "" : key.toLowerCase(Locale.ROOT);
        if (k.contains("margin_floor")) return marginFloor(to);
        Double f = num(from), t = num(to);
        if (CAPS.matcher(k).find() && f != null && t != null && t > f) {
            return Optional.of(new Violation("HC-4", "'" + key + "' is a daily action or dollar cap; learning may not raise it (" + f + " → " + t + ")"));
        }
        if (PHYSICAL.matcher(k).find()) {
            return Optional.of(new Violation("HC-5", "'" + key + "' is a node capacity or carrier cut-off; learning may not change it"));
        }
        return Optional.empty();
    }

    private Optional<Violation> marginFloor(Object to) {
        Double t = num(to);
        double floor = floor();
        if (t != null && t < floor) {
            return Optional.of(new Violation("HC-1", String.format(Locale.ROOT, "the margin floor cannot go below %.2f (proposed %.2f)", floor, t)));
        }
        return Optional.empty();
    }

    private Optional<Violation> ruleAdd(PolicyChange.RuleAdd ra) {
        String expr = nz(ra.expression()) + " " + nz(ra.thenValue());
        for (String token : expr.split("[^A-Za-z0-9_]+")) {
            if (!token.isBlank() && FeatureAllowlist.isProtected(token)) {
                return Optional.of(new Violation("HC-6", "the rule reads '" + token + "', a protected attribute"));
            }
        }
        if (COMPLIANCE.matcher(nz(ra.thenValue())).find() && nz(ra.thenValue()).toLowerCase(Locale.ROOT).matches(".*(release|skip|bypass|waive).*")) {
            return Optional.of(new Violation("HC-2", "the rule would release or bypass a compliance hold"));
        }
        return Optional.empty();
    }

    /** The higher of the tenant's configured floor and the approved objective's {@code constraints.marginFloorPct}. */
    public double floor() {
        double tenant = config.getDouble("sourcing.margin_floor", 0.0);
        double objective = objectives.live().map(ObjectiveProfile::constraints).map(HardConstraints::marginFloorPct).orElse(0.0);
        return Math.max(tenant, objective);
    }

    static double marginFloorPct(Map<String, Object> constraints) {
        if (constraints == null) return 0.0;
        Object v = constraints.get("marginFloorPct");
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static Double num(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o == null) return null;
        try { return Double.parseDouble(String.valueOf(o)); } catch (NumberFormatException e) { return null; }
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /** For tests and the change card: the constraints in plain words. */
    public static final List<String> DESCRIPTIONS = List.of(
        "HC-1 margin floor", "HC-2 compliance holds", "HC-3 refunds, cancellations and pricing are never autonomous",
        "HC-4 daily action and dollar caps", "HC-5 node capacity and carrier cut-offs", "HC-6 no protected attributes");
}
