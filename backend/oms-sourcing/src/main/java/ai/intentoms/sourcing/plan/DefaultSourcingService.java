package ai.intentoms.sourcing.plan;

import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.runtime.DecisionService;
import ai.intentoms.learning.learner.SourcingPolicyBandit;
import ai.intentoms.order.model.FulfilmentGroup;
import ai.intentoms.order.model.IntentProfile;
import ai.intentoms.order.model.MarginSnapshot;
import ai.intentoms.order.model.Note;
import ai.intentoms.order.model.Order;
import ai.intentoms.order.model.OrderLine;
import ai.intentoms.order.store.OrderRepository;
import ai.intentoms.platform.error.ConflictException;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.time.TimeSource;
import ai.intentoms.sourcing.CandidateGenerator;
import ai.intentoms.sourcing.NodeRegistry;
import ai.intentoms.sourcing.SourcingService;
import ai.intentoms.sourcing.config.SourcingSettings;
import ai.intentoms.sourcing.model.Allocation;
import ai.intentoms.sourcing.model.Candidate;
import ai.intentoms.sourcing.model.PlanPackage;
import ai.intentoms.sourcing.model.Policies;
import ai.intentoms.sourcing.model.SourcingPlan;
import ai.intentoms.sourcing.model.SourcingRequest;
import ai.intentoms.sourcing.model.SourcingResult;
import ai.intentoms.sourcing.rates.RateCard;
import ai.intentoms.sourcing.rates.RateCardRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Intent-driven sourcing (FR-SRC-02, FR-SRC-03, FR-SRC-04, FR-SRC-07; GR-01; NFR-05).
 * <ol>
 *   <li>Candidates (FR-SRC-01) → one {@code sourcing_node_score} call (section 9 rules 3, 5) → plans for every policy.</li>
 *   <li>Policy from the order's {@link IntentProfile}: explicit or inferred policy, else the channel default
 *       ({@code sourcing.channel_default_policy}), else ship_as_available. A split-type intent with {@code splitOk == false}
 *       prefers ship_complete. A multi-package pick is checked with the T2 {@code split_acceptability} decision; a
 *       confident "no" falls back to ship_complete when one exists.</li>
 *   <li>Margin floor (GR-01, FR-SRC-04): below the floor without an approval on the order → {@code REQUIRES_APPROVAL} with
 *       alternative plans that meet the floor (later date, pickup, ship complete); nothing but the margin snapshot and
 *       the considered plans is persisted and {@code order.sourcing_blocked_margin_floor} is appended.</li>
 *   <li>Otherwise fulfilment groups (one per package), line group ids and the recomputed margin snapshot are persisted
 *       through {@link OrderRepository#update}, every considered plan lands in {@code sourcing_plan}, and
 *       {@code order.sourced} is appended in the same transaction.</li>
 * </ol>
 */
@Service
public class DefaultSourcingService implements SourcingService {
    public static final String EVT_SOURCED = "order.sourced";
    public static final String EVT_BLOCKED = "order.sourcing_blocked_margin_floor";
    public static final String EVT_FAILED = "order.sourcing_failed";
    public static final String EVT_RESOURCED = "order.resourced";
    public static final String SPLIT_DECISION = "split_acceptability";
    static final List<String> FALLBACK_ORDER = List.of(Policies.SHIP_COMPLETE, Policies.SHIP_AS_AVAILABLE, Policies.SPLIT_UP_TO_K, Policies.EARLIEST_PARTIAL, Policies.PICKUP_CONSOLIDATED);
    private static final Set<String> INACTIVE_GROUP = Set.of("rejected", "cancelled", "shipped", "delivered", "picked_up");
    private static final Logger log = LoggerFactory.getLogger(DefaultSourcingService.class);

    private record Run(Order order, SourcingRequest req, List<Candidate> candidates, SoftScorer.SoftScores soft, List<SourcingPlan> plans) {
        boolean empty() { return plans.isEmpty(); }
    }

    private final OrderRepository orders;
    private final CandidateGenerator candidates;
    private final PlanBuilder planBuilder;
    private final SoftScorer softScorer;
    private final DecisionService decisions;
    private final NodeRegistry nodes;
    private final RateCardRepository rates;
    private final SourcingSettings settings;
    private final SourcingPlanRepository planRepo;
    private final EventStore events;
    private final TimeSource time;
    private final Metrics metrics;
    private final AuthorizationService authz;
    private final SourcingPolicyBandit bandit;

    public DefaultSourcingService(OrderRepository orders, CandidateGenerator candidates, PlanBuilder planBuilder, SoftScorer softScorer, DecisionService decisions, NodeRegistry nodes,
                                  RateCardRepository rates, SourcingSettings settings, SourcingPlanRepository planRepo, EventStore events, TimeSource time, Metrics metrics, AuthorizationService authz,
                                  SourcingPolicyBandit bandit) {
        this.orders = orders;
        this.candidates = candidates;
        this.planBuilder = planBuilder;
        this.softScorer = softScorer;
        this.decisions = decisions;
        this.nodes = nodes;
        this.rates = rates;
        this.settings = settings;
        this.planRepo = planRepo;
        this.events = events;
        this.time = time;
        this.metrics = metrics;
        this.authz = authz;
        this.bandit = bandit;
    }

    @Override
    public List<SourcingPlan> options(String orderId) {
        return metrics.time("oms.sourcing.options", () -> bestPerPolicy(run(orders.get(orderId), Set.of(), Actor.parse(authz.actorRef())).plans()));
    }

    @Override
    @Transactional
    public SourcingResult source(String orderId, Actor actor) {
        return metrics.time("oms.sourcing.source", () -> sourceInternal(orders.get(orderId), Set.of(), actor, new ArrayList<>()));
    }

    @Override
    @Transactional
    public SourcingResult resource(String orderId, String reason, Actor actor) { return resource(orderId, null, reason, actor); }

    @Override
    @Transactional
    public SourcingResult resource(String orderId, String nodeId, String reason, Actor actor) {
        return metrics.time("oms.sourcing.resource", () -> {
            Order order = orders.get(orderId);
            List<FulfilmentGroup> active = order.fulfilmentGroups().stream()
                .filter(g -> g.status() == null || !INACTIVE_GROUP.contains(g.status().toLowerCase()))
                .filter(g -> nodeId == null || nodeId.equals(g.nodeId())).toList();
            if (active.isEmpty()) throw new ConflictException("Order " + orderId + " has no active fulfilment group to re-source" + (nodeId == null ? "" : " at node " + nodeId), Map.of("orderId", orderId));
            Set<String> rejectedNodes = new LinkedHashSet<>();
            Set<String> penalised = new HashSet<>();
            double delta = settings.rejectPenalty();
            for (FulfilmentGroup g : active) {
                rejectedNodes.add(g.nodeId());
                for (FulfilmentGroup.Allocation a : g.allocations()) {
                    if (penalised.add(g.nodeId() + "|" + a.sku())) nodes.penalise(g.nodeId(), a.sku(), delta, "store_reject: " + reason);
                }
            }
            Set<String> rejectedIds = new LinkedHashSet<>();
            List<FulfilmentGroup> groups = new ArrayList<>();
            for (FulfilmentGroup g : order.fulfilmentGroups()) {
                if (active.contains(g)) {
                    rejectedIds.add(g.id());
                    Map<String, Object> ex = new LinkedHashMap<>(g.explain() == null ? Map.of() : g.explain());
                    ex.put("rejectedReason", reason);
                    ex.put("rejectedAt", time.now().toString());
                    groups.add(new FulfilmentGroup(g.id(), g.nodeId(), g.policy(), g.allocations(), g.promiseDate(), g.onTimeProbability(), g.shipMethod(), g.estimatedCost(), "rejected", g.sourcingPlanId(), g.decisionRecordId(), ex));
                } else groups.add(g);
            }
            List<OrderLine> lines = order.lines().stream().map(l -> rejectedIds.contains(l.fulfilmentGroupId()) ? l.withFulfilmentGroup(null) : l).toList();
            Order updated = orders.update(order.withFulfilmentGroups(groups).withLines(lines));
            List<String> reasons = new ArrayList<>();
            reasons.add("re-sourced after reject at " + rejectedNodes + ": " + reason);
            SourcingResult result = sourceInternal(updated, rejectedNodes, actor, reasons);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("reason", reason == null ? "" : reason);
            payload.put("rejectedNodes", List.copyOf(rejectedNodes));
            payload.put("rejectedGroupIds", List.copyOf(rejectedIds));
            payload.put("penaltyDelta", delta);
            payload.put("status", result.status().name());
            payload.put("planId", result.plan() == null ? "" : result.plan().id());
            payload.put("newGroupIds", result.plan() == null ? List.of() : orders.get(orderId).fulfilmentGroups().stream().filter(g -> result.plan().id().equals(g.sourcingPlanId())).map(FulfilmentGroup::id).toList());
            events.append("order", orderId, new NewEvent(EVT_RESOURCED, payload, actor).withCorrelation(orderId, null));
            metrics.count("oms.sourcing.resourced", "status", result.status().name());
            return result;
        });
    }

    // ---- the run ----------------------------------------------------------------------------------------------------

    private Run run(Order order, Set<String> excluded, Actor actor) {
        SourcingRequest.Constraints constraints = constraintsFor(order).excluding(excluded);
        SourcingRequest req = SourcingRequest.from(order, constraints);
        List<Candidate> cands = candidates.generate(req, 0);
        if (cands.isEmpty()) return new Run(order, req, cands, SoftScorer.SoftScores.neutral("no candidates"), List.of());
        RateCard card = rates.current();
        SoftScorer.SoftScores soft = softScorer.score(req, cands, card, actor);
        Map<String, Double> softMap = new LinkedHashMap<>();
        double floor = SoftScorer.floorScore(soft.byNode());
        for (Candidate c : cands) softMap.put(c.nodeId(), soft.byNode().getOrDefault(c.nodeId(), floor));
        Instant now = time.now();
        BuildContext ctx = new BuildContext(req, cands, card, softMap, now, now.atZone(ZoneOffset.UTC).toLocalDate(), settings.maxSplitPackages(), settings.businessDaysOnly(),
            settings.splitPenaltyUsd(card.splitPenaltyUsd()), settings.marginFloor(), settings.pickupRadiusKm(), settings.weights(), nodes::penalty);
        List<SourcingPlan> plans = planBuilder.build(ctx).stream().map(p -> p.withExplain("softScoring", soft.explain())).toList();
        return new Run(order, req, cands, soft, plans);
    }

    private SourcingResult sourceInternal(Order order, Set<String> excluded, Actor actor, List<String> reasons) {
        Run run = run(order, excluded, actor);
        Instant now = time.now();
        if (run.empty()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("reason", "no_feasible_node");
            payload.put("lineIds", run.req().lines().stream().map(SourcingRequest.RequestLine::lineId).toList());
            payload.put("excludedNodes", List.copyOf(excluded));
            payload.put("candidates", run.candidates().size());
            events.append("order", order.id(), new NewEvent(EVT_FAILED, payload, actor).withCorrelation(order.id(), null));
            metrics.count("oms.sourcing.failed");
            reasons.add(run.candidates().isEmpty() ? "no node is active, capable and stocked for any line" : "no plan could be built from the candidates");
            return new SourcingResult(SourcingResult.Status.NO_FEASIBLE_NODE, null, List.of(), run.soft().decisionRecordId(), reasons);
        }
        String policy = resolvePolicy(order, run, reasons, actor);
        SourcingPlan chosen = bestFor(run.plans(), policy);
        if (chosen == null) {
            for (String p : FALLBACK_ORDER) { chosen = bestFor(run.plans(), p); if (chosen != null) { reasons.add("no " + policy + " plan is feasible; fell back to " + p); policy = p; break; } }
        }
        if (chosen == null) chosen = run.plans().get(0);
        if (chosen.packageCount() > 1) chosen = splitAcceptability(order, run, chosen, actor, reasons);
        String recordId = run.soft().decisionRecordId();
        final SourcingPlan plan = chosen.withExplain("policyReasons", List.copyOf(reasons));

        boolean approval = order.margin() != null && order.margin().approvalPresent();
        double floor = settings.marginFloor();
        if (!plan.meetsMarginFloor() && !approval) {
            List<SourcingPlan> alternatives = bestPerPolicy(run.plans()).stream().filter(p -> p.meetsMarginFloor() && !p.id().equals(plan.id())).toList();
            planRepo.saveAll(order.id(), run.plans(), null, now);
            orders.update(order.withMargin(snapshot(plan, floor, order.margin(), now)));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("planId", plan.id());
            payload.put("policy", plan.policy());
            payload.put("marginRatio", plan.pnl().marginRatio());
            payload.put("contributionMargin", plan.pnl().contributionMargin());
            payload.put("floor", floor);
            payload.put("alternatives", alternatives.stream().map(p -> Map.of("planId", p.id(), "policy", p.policy(), "eta", String.valueOf(p.latestEta()), "totalCost", p.totalCost().amount(), "marginRatio", p.pnl().marginRatio())).toList());
            payload.put("decisionRecordId", recordId == null ? "" : recordId);
            events.append("order", order.id(), new NewEvent(EVT_BLOCKED, payload, actor).withCorrelation(order.id(), null));
            metrics.count("oms.sourcing.margin_floor_blocked");
            reasons.add(String.format(java.util.Locale.ROOT, "contribution margin %.4f is below the floor %.4f and no approval is present (GR-01, FR-SRC-04)", plan.pnl().marginRatio(), floor));
            return new SourcingResult(SourcingResult.Status.REQUIRES_APPROVAL, plan, alternatives, recordId, reasons);
        }
        if (!plan.meetsMarginFloor()) reasons.add("below the margin floor but approved (task " + order.margin().approvalTaskId() + ")");

        persist(order, run, plan, recordId, floor, now, actor);
        List<SourcingPlan> alternatives = bestPerPolicy(run.plans()).stream().filter(p -> !p.id().equals(plan.id())).toList();
        metrics.count("oms.sourcing.sourced", "policy", plan.policy(), "packages", String.valueOf(plan.packageCount()));
        return new SourcingResult(SourcingResult.Status.SOURCED, plan, alternatives, recordId, reasons);
    }

    private void persist(Order order, Run run, SourcingPlan plan, String recordId, double floor, Instant now, Actor actor) {
        List<FulfilmentGroup> groups = new ArrayList<>();
        for (FulfilmentGroup g : order.fulfilmentGroups()) if (g.status() != null && INACTIVE_GROUP.contains(g.status().toLowerCase())) groups.add(g);
        Map<String, String> lineGroup = new HashMap<>();
        List<Map<String, Object>> nodeExplains = plan.explain().get("nodes") instanceof List<?> l ? l.stream().map(o -> (Map<String, Object>) o).toList() : List.of();
        String shipMethod = Policies.PICKUP_CONSOLIDATED.equals(plan.policy()) ? SourcingRequest.METHOD_PICKUP : run.req().shipMethod();
        for (int i = 0; i < plan.packages().size(); i++) {
            PlanPackage pk = plan.packages().get(i);
            String id = Ids.prefixed("fg");
            List<FulfilmentGroup.Allocation> allocs = new ArrayList<>();
            for (Allocation a : pk.allocations()) { allocs.add(new FulfilmentGroup.Allocation(a.lineId(), a.sku(), a.qty())); lineGroup.putIfAbsent(a.lineId(), id); }
            Map<String, Object> explain = new LinkedHashMap<>(i < nodeExplains.size() ? nodeExplains.get(i) : Map.of());
            explain.put("planScore", plan.score());
            explain.put("planComponents", plan.explain().getOrDefault("components", Map.of()));
            explain.put("policyReasons", plan.explain().getOrDefault("policyReasons", List.of()));
            groups.add(new FulfilmentGroup(id, pk.nodeId(), plan.policy(), allocs, pk.eta(), pk.onTimeProbability(), shipMethod, pk.totalCost(), "planned", plan.id(), recordId, explain));
        }
        List<OrderLine> lines = order.lines().stream().map(l -> lineGroup.containsKey(l.lineId()) ? l.withFulfilmentGroup(lineGroup.get(l.lineId())) : l).toList();
        orders.update(order.withFulfilmentGroups(groups).withLines(lines).withMargin(snapshot(plan, floor, order.margin(), now)));
        planRepo.saveAll(order.id(), run.plans(), plan.id(), now);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("planId", plan.id());
        payload.put("policy", plan.policy());
        payload.put("packages", plan.packages().stream().map(pk -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nodeId", pk.nodeId());
            m.put("allocations", pk.allocations());
            m.put("eta", String.valueOf(pk.eta()));
            m.put("onTime", pk.onTimeProbability());
            m.put("cost", pk.totalCost().amount());
            return m;
        }).toList());
        payload.put("totalCost", plan.totalCost().amount());
        payload.put("margin", plan.pnl().marginRatio());
        payload.put("contributionMargin", plan.pnl().contributionMargin());
        payload.put("meetsFloor", plan.meetsMarginFloor());
        payload.put("decisionRecordId", recordId == null ? "" : recordId);
        payload.put("softScores", run.soft().byNode());
        payload.put("score", plan.score());
        payload.put("unsourcedLineIds", plan.unsourcedLineIds());
        payload.put("fulfilmentGroupIds", groups.stream().filter(g -> plan.id().equals(g.sourcingPlanId())).map(FulfilmentGroup::id).toList());
        events.append("order", order.id(), new NewEvent(EVT_SOURCED, payload, actor).withCorrelation(order.id(), recordId));
    }

    // ---- policy ---------------------------------------------------------------------------------------------------

    String resolvePolicy(Order order, Run run, List<String> reasons, Actor actor) {
        if (run.req().isPickup()) { reasons.add("pickup order: pickup_consolidated"); return Policies.PICKUP_CONSOLIDATED; }
        IntentProfile intent = order.intent();
        String policy = null;
        if (intent != null && Policies.known(intent.policy())) {
            policy = intent.policy();
            reasons.add("policy " + policy + " from intent (" + intent.source() + ", confidence " + intent.confidence() + ")");
        } else {
            String dflt = settings.channelDefaultPolicy(order.channel());
            policy = Policies.known(dflt) ? dflt : Policies.SHIP_AS_AVAILABLE;
            reasons.add("intent " + (intent == null ? "absent" : intent.policy()) + "; channel '" + order.channel() + "' default " + policy + (Policies.known(dflt) ? "" : " (system default)"));
        }
        if (Policies.splitting(policy) && intent != null && Boolean.FALSE.equals(intent.splitOk()) && bestFor(run.plans(), Policies.SHIP_COMPLETE) != null) {
            reasons.add("intent says splits are not acceptable; preferring ship_complete");
            policy = Policies.SHIP_COMPLETE;
        }
        return applyBandit(order, run, policy, reasons, actor);
    }

    /** FS-01: logs a sampled arm/propensity against the policies that actually clear the margin floor here, and
     * only overrides the policy above when the bandit learner's autonomy is L2 or higher (see {@link
     * SourcingPolicyBandit}) — below that it's pure observation and this method is a no-op on the returned policy. */
    private String applyBandit(Order order, Run run, String defaultPolicy, List<String> reasons, Actor actor) {
        List<String> eligible = bestPerPolicy(run.plans()).stream().filter(SourcingPlan::meetsMarginFloor).map(SourcingPlan::policy).toList();
        if (eligible.isEmpty()) return defaultPolicy;
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("channel", order.channel());
        context.put("intentPolicy", order.intent() == null ? null : order.intent().policy());
        context.put("customerTier", order.customer() == null ? null : order.customer().tier());
        context.put("lineCount", order.lines() == null ? 0 : order.lines().size());
        try {
            SourcingPolicyBandit.Choice choice = bandit.choose(order.id(), eligible, defaultPolicy, context, actor.ref());
            if (choice.acted() && !choice.policy().equals(defaultPolicy) && eligible.contains(choice.policy())) {
                reasons.add("sourcing_policy_bandit chose " + choice.policy() + " over " + defaultPolicy + " (propensity " + choice.propensity() + ", record " + choice.decisionRecordId() + ")");
                return choice.policy();
            }
        } catch (RuntimeException e) {
            log.warn("Sourcing policy bandit unavailable for order {}: {}", order.id(), e.getMessage());
        }
        return defaultPolicy;
    }

    /** T2 {@code split_acceptability}: a confident "no" (act band) on a multi-package plan falls back to ship_complete when available. */
    private SourcingPlan splitAcceptability(Order order, Run run, SourcingPlan chosen, Actor actor, List<String> reasons) {
        SourcingPlan complete = bestFor(run.plans(), Policies.SHIP_COMPLETE);
        if (complete == null) return chosen;
        try {
            Map<String, Object> input = splitInput(order, run, chosen);
            DecisionResult r = decisions.decide(SPLIT_DECISION, input, DecisionOptions.live("order", order.id(), actor));
            boolean acceptable = r.value() instanceof Boolean b ? b : r.value() instanceof Number n ? n.doubleValue() >= 0.5 : true;
            String note = "split_acceptability " + r.layer() + "/" + r.action() + " value=" + r.value() + " confidence=" + r.confidence() + " record=" + r.recordId();
            if (r.acts() && !acceptable) {
                reasons.add(note + ": customer unlikely to accept a split; falling back to ship_complete");
                return complete.withExplain("splitAcceptability", Map.of("recordId", r.recordId(), "value", String.valueOf(r.value()), "fellBackFrom", chosen.id()));
            }
            reasons.add(note + ": keeping the " + chosen.packageCount() + "-package plan");
            return chosen.withExplain("splitAcceptability", Map.of("recordId", r.recordId() == null ? "" : r.recordId(), "value", String.valueOf(r.value()), "action", r.action()));
        } catch (RuntimeException e) {
            log.warn("split_acceptability unavailable for order {}: {}", order.id(), e.getMessage());
            reasons.add("split_acceptability unavailable (" + e.getMessage() + "); keeping the chosen plan");
            return chosen;
        }
    }

    private Map<String, Object> splitInput(Order order, Run run, SourcingPlan chosen) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("channel", order.channel());
        o.put("notes", order.notes() == null ? "" : String.join(" ", order.notes().stream().map(Note::text).filter(t -> t != null).toList()));
        o.put("intent", order.intent() == null ? "" : order.intent().policy());
        Map<String, Object> cust = new LinkedHashMap<>();
        cust.put("tier", order.customer() == null || order.customer().tier() == null ? "" : order.customer().tier());
        Map<String, Object> history = order.customer() == null || order.customer().history() == null ? Map.of() : order.customer().history();
        cust.put("history", Map.of("split_acceptance_rate", history.getOrDefault("split_acceptance_rate", 0.5), "split_complaints", history.getOrDefault("split_complaints", 0)));
        List<Map<String, Object>> options = new ArrayList<>();
        for (SourcingPlan p : bestPerPolicy(run.plans())) {
            options.add(Map.of("packages", p.packageCount(), "eta", String.valueOf(p.latestEta()), "cost_delta", p.totalCost().subtract(chosen.totalCost()).amount()));
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("order", o);
        input.put("customer", cust);
        input.put("plan_options", options);
        input.put("chosen_plan", Map.of("margin", chosen.pnl().marginRatio(), "packages", chosen.packageCount()));
        return input;
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private SourcingRequest.Constraints constraintsFor(Order order) {
        Map<String, Object> ext = order.extensions() == null ? Map.of() : order.extensions();
        if (ext.get("sourcing") instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked") Map<String, Object> mm = (Map<String, Object>) m;
            return SourcingRequest.Constraints.fromMap(mm);
        }
        return SourcingRequest.Constraints.fromMap(settings.orderTypeConstraints(order.orderType()));
    }

    static SourcingPlan bestFor(List<SourcingPlan> plans, String policy) {
        return plans.stream().filter(p -> policy.equals(p.policy())).max(Comparator.comparingDouble(SourcingPlan::score).thenComparing(Comparator.comparing(SourcingPlan::id).reversed())).orElse(null);
    }

    /** Best variant per policy, best first. */
    static List<SourcingPlan> bestPerPolicy(List<SourcingPlan> plans) {
        Map<String, SourcingPlan> best = new LinkedHashMap<>();
        for (SourcingPlan p : plans) best.merge(p.policy(), p, (a, b) -> b.score() > a.score() ? b : a);
        return best.values().stream().sorted(Comparator.comparingDouble(SourcingPlan::score).reversed()).toList();
    }

    private static MarginSnapshot snapshot(SourcingPlan plan, double floor, MarginSnapshot previous, Instant now) {
        return new MarginSnapshot(plan.pnl().netRevenue(), plan.pnl().contributionMargin(), plan.pnl().marginRatio(), floor, !plan.meetsMarginFloor(),
            previous != null && previous.approvalPresent(), previous == null ? null : previous.approvalTaskId(), now);
    }
}
