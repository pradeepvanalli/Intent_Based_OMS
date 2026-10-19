package ai.intentoms.learning.promotion;

import ai.intentoms.learning.governance.LearnerDescriptor;
import ai.intentoms.learning.governance.LearnerRegistry;
import ai.intentoms.learning.governance.LiveFamilies;
import ai.intentoms.learning.model.PolicyChange;
import ai.intentoms.learning.model.Proposal;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * N5, N1, E-3: which learner governs a proposal, and which decisions show whether it hurt once promoted. A proposal is
 * governed by the learner that owns its target, whoever drafted it, so that learner's autonomy, kill switch, pause and
 * weekly budget apply:
 * <ol>
 *   <li>live use ({@code parameter:live:<family>}): the {@code learner} the change names, else the learner that feeds the family;</li>
 *   <li>any other change: the learner whose job the change is (threshold moves, rule retirement, new rules, sourcing
 *       weights, provider routes, the learned fact a parameter change moves), whoever drafted it;</li>
 *   <li>a learner's own draft of a change no learner owns ({@code system:<learner>}, or {@code system:demo-seed.<learner>}).</li>
 * </ol>
 * D-4: when one learner drafts a change another learner owns (the band tuner moving a fraud decision's threshold), both
 * learners' states apply and the stricter wins ({@link #governors}). Only a change no learner owns or drafted falls back
 * to tier rules and the tenant's per-target weekly budget.
 */
@Component
public class ProposalOwnership {
    public static final Set<String> FRAUD_DECISIONS = Set.of("payment_risk", "rto_risk", "return_risk", "address_risk");
    private static final String PROVIDER_ROUTE_PREFIX = "learning.provider_route.";
    private static final Map<String, String> FACT_OWNERS = Map.ofEntries(
        Map.entry("SAFETY_STOCK_UNITS", "inventory_safety_stock"), Map.entry("RESERVATION_TTL_MINUTES", "inventory_reservation_ttl"),
        Map.entry("INBOUND_RELIABILITY", "inventory_inbound_reliability"), Map.entry("STOCK_AGE_DAYS", "inventory_stock_age"),
        Map.entry("AGED_STOCK_WEIGHT", "inventory_stock_age"), Map.entry("BACKORDER_CONVERSION_RATE", "inventory_backorder"),
        Map.entry("TRANSFER_RECOMMENDATION", "inventory_transfer"),
        Map.entry("NODE_REJECT_RATE", "node_reliability"), Map.entry("NODE_ON_TIME_RATE", "node_reliability"), Map.entry("NODE_REJECT_PENALTY", "node_reliability"),
        Map.entry("NODE_BACKLOG_BREACH_CURVE", "node_backlog_breach"), Map.entry("LANE_ON_TIME_RATE", "lane_transit"),
        Map.entry("LANE_CALIBRATION_OFFSET", "lane_transit"), Map.entry("LANE_CALIBRATION_ERROR", "lane_transit"),
        Map.entry("PROMISE_BUFFER_DAYS", "promise_buffer"), Map.entry("WARN_EARLY_THRESHOLD", "warn_early"), Map.entry("APPEASEMENT_LEVEL", "appeasement_level"),
        Map.entry("CALIBRATION_CURVE", "confidence_calibrator"), Map.entry("CALIBRATION_ERROR", "confidence_calibrator"),
        Map.entry("SOURCING_POLICY_PROPENSITY", "bandit_reward"), Map.entry("ADDRESS_REUSE_COUNT", "fraud_abuse_scanner"),
        Map.entry("FRAUD_SIGNAL_RATE", "fraud_signals"), Map.entry("FALSE_POSITIVE_RATE", "fraud_threshold_tuner"),
        Map.entry("AGENT_REPUTATION", "agent_reputation"), Map.entry("DISPUTE_RATE", "agent_reputation"),
        Map.entry("PROVIDER_ACCURACY", "provider_routing"), Map.entry("PROVIDER_COST_PER_CALL", "provider_routing"), Map.entry("PROVIDER_LATENCY_MS", "provider_routing"),
        Map.entry("SPLIT_TOLERANCE", "ci_preference"), Map.entry("SPEED_VS_COST", "ci_preference"), Map.entry("PICKUP_PREFERENCE", "ci_preference"),
        Map.entry("PREFERRED_CONTACT_CHANNEL", "ci_preference"), Map.entry("SEGMENT_MEMBERSHIP", "ci_segment_discovery"));
    private static final Map<String, String> CONFIG_OWNERS = Map.of(
        "sourcing.weights", "sourcing_weights", "cs.appeasement_default_usd", "appeasement_level", "learning.bandit.exploration", "sourcing_policy_bandit");

    private final LearnerRegistry registry;

    public ProposalOwnership(LearnerRegistry registry) { this.registry = registry; }

    /**
     * The governing learner's key, or empty when no learner owns the change. D-4: the target's owner governs, whoever
     * drafted the change; a learner's draft is governed by that learner only when no learner owns the target.
     */
    public Optional<String> owner(Proposal p) {
        String family = liveFamily(p);
        if (family != null) {
            Object named = p.changeset().changes().isEmpty() || !(p.changeset().changes().get(0) instanceof PolicyChange.ParameterChange pc)
                || !(pc.to() instanceof Map<?, ?> to) ? null : to.get("learner");
            if (named != null && registered(String.valueOf(named))) return Optional.of(String.valueOf(named));
            return registry.descriptors().stream().filter(d -> d.liveParameters().contains(family)).map(LearnerDescriptor::key).findFirst();
        }
        Optional<String> target = byTarget(p);
        if (target.isPresent()) return target;
        String author = authorLearner(p.createdBy());
        return author != null && registered(author) ? Optional.of(author) : Optional.empty();
    }

    /**
     * D-4: every learner whose governance applies to the change, the governing owner first, then the learner that drafted
     * it when that is a different one. The promotion gate applies the stricter of their states: either one switched off
     * or paused blocks the change.
     */
    public List<String> governors(Proposal p) {
        Set<String> out = new LinkedHashSet<>();
        owner(p).ifPresent(out::add);
        String author = authorLearner(p.createdBy());
        if (author != null && registered(author)) out.add(author);
        return List.copyOf(out);
    }

    /** How the owner was found, for the change card and NF-09 attribution: "learner", "live-use", "target" or "none". */
    public String ownedBy(Proposal p) {
        if (liveFamily(p) != null) return owner(p).isPresent() ? "live-use" : "none";
        if (byTarget(p).isPresent()) return "target";
        String author = authorLearner(p.createdBy());
        return author != null && registered(author) ? "learner" : "none";
    }

    /** EX-05: the decisions whose guardrails show whether this promoted change hurt. */
    public List<String> watchedDecisions(Proposal p) {
        Set<String> out = new LinkedHashSet<>();
        String type = p.changeset().target().type();
        String name = nameOf(p);
        String family = liveFamily(p);
        if ("decision".equals(type)) out.add(name);
        if (family != null) {
            LiveFamilies.of(family).ifPresent(d -> out.addAll(d.affectedDecisions()));
            registry.descriptors().stream().filter(d -> d.liveParameters().contains(family)).forEach(d -> out.addAll(d.outcomeDecisions()));
        }
        if ("config".equals(type) && name.startsWith(PROVIDER_ROUTE_PREFIX)) out.add(name.substring(PROVIDER_ROUTE_PREFIX.length()));
        for (String learner : governors(p)) registry.descriptor(learner).ifPresent(d -> out.addAll(d.outcomeDecisions()));
        return List.copyOf(out);
    }

    /** {@code system:band_threshold} and {@code system:demo-seed.band_threshold} both name band_threshold. */
    public static String authorLearner(String createdBy) {
        if (createdBy == null || !createdBy.startsWith("system:")) return null;
        String key = createdBy.substring("system:".length());
        return key.startsWith("demo-seed.") ? key.substring("demo-seed.".length()) : key;
    }

    private static String liveFamily(Proposal p) {
        String name = nameOf(p);
        return "parameter".equals(p.changeset().target().type()) && name.startsWith("live:") ? name.substring("live:".length()) : null;
    }

    private static String nameOf(Proposal p) { return p.changeset().target().name() == null ? "" : p.changeset().target().name(); }

    private Optional<String> byTarget(Proposal p) {
        String name = nameOf(p);
        PolicyChange first = p.changeset().changes().isEmpty() ? null : p.changeset().changes().get(0);
        String candidate = switch (p.changeset().target().type()) {
            case "decision" -> {
                boolean fraud = FRAUD_DECISIONS.contains(name);
                yield switch (first) {
                    case PolicyChange.ThresholdChange t -> fraud ? "fraud_threshold_tuner" : "band_threshold";
                    case PolicyChange.RuleRetire r -> "rule_utilization";
                    case PolicyChange.RuleAdd r -> fraud ? "fraud_abuse_scanner" : "override_to_rule";
                    case null, default -> null;
                };
            }
            case "config" -> name.startsWith(PROVIDER_ROUTE_PREFIX) ? "provider_routing" : CONFIG_OWNERS.get(name);
            case "parameter" -> {
                String[] parts = name.split(":", 3);
                yield parts.length == 3 ? FACT_OWNERS.get(parts[2]) : null;
            }
            default -> null;
        };
        return candidate != null && registered(candidate) ? Optional.of(candidate) : Optional.empty();
    }

    private boolean registered(String key) { return registry.descriptor(key).isPresent(); }
}
