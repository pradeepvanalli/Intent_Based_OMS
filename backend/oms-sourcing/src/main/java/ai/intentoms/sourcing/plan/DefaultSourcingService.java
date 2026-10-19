package ai.intentoms.sourcing.plan;

import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.model.Mode;
import ai.intentoms.decision.runtime.DecisionService;
import ai.intentoms.learning.governance.ParameterResolver;
import ai.intentoms.learning.hooks.NodeReliabilityFeatures;
import ai.intentoms.learning.learner.BacklogBreachCurve;
import ai.intentoms.learning.learner.LaneDeliveryLearner;
import ai.intentoms.learning.learner.NodeReliabilityLearner;
import ai.intentoms.learning.learner.SourcingPolicyBandit;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.LearnedFactCache;
import ai.intentoms.learning.store.ObjectiveProfileRepository;
import ai.intentoms.platform.config.TenantConfigStore;
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
import ai.intentoms.sourcing.learning.LaneKnowledge;
import ai.intentoms.sourcing.model.Allocation;
import ai.intentoms.sourcing.model.Candidate;
import ai.intentoms.sourcing.model.PlanPackage;
import ai.intentoms.sourcing.model.Policies;
import ai.intentoms.sourcing.model.SourcingPlan;
import ai.intentoms.sourcing.model.SourcingRequest;
import ai.intentoms.sourcing.model.SourcingResult;
import ai.intentoms.sourcing.rates.RateCard;
import ai.intentoms.sourcing.rates.RateCardRepository;
import ai.intentoms.sourcing.rates.Transit;
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
 *
 * <p>Self-optimizing hooks, each live only when promoted (see {@code ParameterResolver}): FS-01 the sourcing-policy
 * bandit, offered only policies that clear the margin floor, would not deliver later than the default policy and fit
 * the order type (never store pickup for a ship-to order), logged with the policy finally used; FS-03 / CX-01 learned
 * lane transit and calibration in candidates and plans ({@link LaneKnowledge}); FS-04 plan weights from
 * {@code sourcing.weights}; FS-05 load shifting away from nodes whose learned backlog-to-breach curve says a breach is
 * likely at their current backlog ({@code sourcing.node_backlog_breach}, threshold
 * {@code sourcing.breach_shift_threshold}); FS-06 the learned per-node reject penalty ({@code sourcing.reject_penalty});
 * IU-03 a tie-break toward aged stock when another plan of the chosen policy costs about the same
 * ({@code sourcing.aged_stock_tiebreak}; the tolerance grows with the objective's aged-stock weight).
 */
@Service
public class DefaultSourcingService implements SourcingService {
    public static final String EVT_SOURCED = "order.sourced";
    public static final String EVT_BLOCKED = "order.sourcing_blocked_margin_floor";
    public static final String EVT_FAILED = "order.sourcing_failed";
    public static final String EVT_RESOURCED = "order.resourced";
    public static final String SPLIT_DECISION = "split_acceptability";
    /** The re-sourcing reason the order workflow gives when its own reservation found the planned stock gone. */
    public static final String RESERVATION_SHORTAGE = "insufficient_atp";
    static final List<String> FALLBACK_ORDER = List.of(Policies.SHIP_COMPLETE, Policies.SHIP_AS_AVAILABLE, Policies.SPLIT_UP_TO_K, Policies.EARLIEST_PARTIAL, Policies.PICKUP_CONSOLIDATED);
    private static final Set<String> INACTIVE_GROUP = Set.of("rejected", "cancelled", "shipped", "delivered", "picked_up");
    private static final Logger log = LoggerFactory.getLogger(DefaultSourcingService.class);

    private record Run(Order order, SourcingRequest req, List<Candidate> candidates, SoftScorer.SoftScores soft, List<SourcingPlan> plans, Map<String, Double> loadShift,
                       String fulfillmentGroupId) {
        boolean empty() { return plans.isEmpty(); }
    }

    /** The bandit draw made while choosing the policy, logged once the final policy is known. */
    private static final class BanditCall {
        SourcingPolicyBandit.Draw draw;
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
    private final ParameterResolver parameters;
    private final LearnedFactCache facts;
    private final TenantConfigStore config;
    private final LaneKnowledge lanes;
    private final ObjectiveProfileRepository objectives;

    public static final String BREACH_SHIFT_THRESHOLD = "sourcing.breach_shift_threshold";
    public static final String AGED_STOCK_PARAMETER = "sourcing.aged_stock_tiebreak";
    public static final String AGED_STOCK_COST_PCT = "sourcing.aged_stock_tiebreak_cost_pct";
    public static final String BANDIT_MAX_ETA_SLIP_DAYS = "sourcing.bandit.max_eta_slip_days";

    public DefaultSourcingService(OrderRepository orders, CandidateGenerator candidates, PlanBuilder planBuilder, SoftScorer softScorer, DecisionService decisions, NodeRegistry nodes,
                                  RateCardRepository rates, SourcingSettings settings, SourcingPlanRepository planRepo, EventStore events, TimeSource time, Metrics metrics, AuthorizationService authz,
                                  SourcingPolicyBandit bandit, ParameterResolver parameters, LearnedFactCache facts, TenantConfigStore config, LaneKnowledge lanes,
                                  ObjectiveProfileRepository objectives) {
        this.lanes = lanes;
        this.objectives = objectives;
        this.parameters = parameters;
        this.facts = facts;
        this.config = config;
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
        Order order = orders.get(orderId);
        // E-1: read-only (CS screen, replays, re-promise previews): scored in SHADOW mode, never a LIVE record or episode.
        return asSubject(order, () -> metrics.time("oms.sourcing.options", () -> bestPerPolicy(run(order, Set.of(), Actor.parse(authz.actorRef()), Mode.SHADOW, null).plans())));
    }

    @Override
    @Transactional
    public SourcingResult source(String orderId, Actor actor) {
        Order order = orders.get(orderId);
        return asSubject(order, () -> metrics.time("oms.sourcing.source", () -> sourceInternal(order, Set.of(), actor, new ArrayList<>(), null)));
    }

    /**
     * EX-04: every learned value read while planning this order (bandit, reject penalty, backlog curves, aged-stock
     * tie-break, lane transit) is read for the order's customer, so a holdout customer stays on the static baseline.
     */
    private <T> T asSubject(Order order, java.util.function.Supplier<T> body) {
        return ai.intentoms.learning.governance.LearningSubject.with(
            ai.intentoms.learning.governance.LearningSubject.routingKey("order", order.id(), order.customerRef()), body);
    }

    @Override
    @Transactional
    public SourcingResult resource(String orderId, String reason, Actor actor) { return resource(orderId, null, reason, actor); }

    @Override
    @Transactional
    public SourcingResult resource(String orderId, String nodeId, String reason, Actor actor) {
        Order subject = orders.get(orderId);
        return asSubject(subject, () -> metrics.time("oms.sourcing.resource", () -> {
            Order order = subject;
            List<FulfilmentGroup> active = order.fulfilmentGroups().stream()
                .filter(g -> g.status() == null || !INACTIVE_GROUP.contains(g.status().toLowerCase()))
                .filter(g -> nodeId == null || nodeId.equals(g.nodeId())).toList();
            if (active.isEmpty()) throw new ConflictException("Order " + orderId + " has no active fulfilment group to re-source" + (nodeId == null ? "" : " at node " + nodeId), Map.of("orderId", orderId));
            Set<String> rejectedNodes = new LinkedHashSet<>();
            Set<String> penalised = new HashSet<>();
            Map<String, Double> deltas = new LinkedHashMap<>();
            // FS-02 / FS-06 (audit B3-1): only a node that refused is penalised and learned as a reject. When the OMS's own
            // reservation lost a race for the last units (stale ATP inside the OMS), the node never refused anything.
            boolean refused = nodeRefused(reason);
            for (FulfilmentGroup g : active) {
                rejectedNodes.add(g.nodeId());
                if (!refused) continue;
                double delta = deltas.computeIfAbsent(g.nodeId(), this::rejectPenalty);
                for (FulfilmentGroup.Allocation a : g.allocations()) {
                    if (penalised.add(g.nodeId() + "|" + a.sku())) nodes.penalise(g.nodeId(), a.sku(), delta, "store_reject: " + reason);
                }
            }
            double delta = !refused ? 0.0 : deltas.values().stream().mapToDouble(Double::doubleValue).max().orElse(settings.rejectPenalty());
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
            reasons.add(refused ? "re-sourced after reject at " + rejectedNodes + ": " + reason
                : "re-sourced away from " + rejectedNodes + " without a node penalty, the node did not refuse: " + reason);
            // OC-02: the re-sourcing decision is about the rejected group, so its episode is keyed by that group; the outcomes
            // it earns are those of the replacement groups it plans (order.sourced names them, GroupLineage joins them).
            SourcingResult result = sourceInternal(updated, rejectedNodes, actor, reasons, rejectedIds.iterator().next());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("reason", reason == null ? "" : reason);
            payload.put("rejectedNodes", List.copyOf(rejectedNodes));
            payload.put("nodeRejected", refused);
            payload.put("rejectedGroupIds", List.copyOf(rejectedIds));
            // OC-02: the units the refused groups held, so the choice that planned them is scored on what it failed to ship.
            payload.put("rejectedUnits", order.fulfilmentGroups().stream().filter(g -> rejectedIds.contains(g.id()))
                .flatMap(g -> g.allocations().stream()).mapToInt(FulfilmentGroup.Allocation::qty).sum());
            payload.put("penaltyDelta", delta);
            payload.put("penaltyByNode", deltas);
            payload.put("status", result.status().name());
            payload.put("planId", result.plan() == null ? "" : result.plan().id());
            payload.put("newGroupIds", result.plan() == null ? List.of() : orders.get(orderId).fulfilmentGroups().stream().filter(g -> result.plan().id().equals(g.sourcingPlanId())).map(FulfilmentGroup::id).toList());
            events.append("order", orderId, new NewEvent(EVT_RESOURCED, payload, actor).withCorrelation(orderId, null));
            metrics.count("oms.sourcing.resourced", "status", result.status().name());
            return result;
        }));
    }

    // ---- the run ----------------------------------------------------------------------------------------------------

    private Run run(Order order, Set<String> excluded, Actor actor, Mode mode, String fulfillmentGroupId) {
        SourcingRequest.Constraints constraints = constraintsFor(order).excluding(excluded);
        SourcingRequest req = SourcingRequest.from(order, constraints);
        List<Candidate> cands = candidates.generate(req, 0);
        if (cands.isEmpty()) return new Run(order, req, cands, SoftScorer.SoftScores.neutral("no candidates"), List.of(), Map.of(), fulfillmentGroupId);
        RateCard card = rates.current();
        SoftScorer.SoftScores soft = softScorer.score(req, cands, card, actor, mode, fulfillmentGroupId);
        Map<String, Double> softMap = new LinkedHashMap<>();
        double floor = SoftScorer.floorScore(soft.byNode());
        for (Candidate c : cands) softMap.put(c.nodeId(), soft.byNode().getOrDefault(c.nodeId(), floor));
        Instant now = time.now();
        Map<String, Double> calibration = new LinkedHashMap<>();
        if (!req.isPickup()) {
            String zip3 = LaneDeliveryLearner.zip3(req.destination().zip());
            for (Candidate c : cands) {
                double offset = lanes.calibration(req.shipMethod(), c.nodeId(), zip3);
                if (offset != 0.0) calibration.put(c.nodeId(), offset);
            }
        }
        Map<String, Double> loadShift = breachPenalties(cands);
        PenaltyLookup penalties = loadShift.isEmpty() ? nodes::penalty : (n, sku) -> Math.min(0.9, nodes.penalty(n, sku) + loadShift.getOrDefault(n, 0.0));
        List<SourcingPlan> plans = List.of();
        if (!loadShift.isEmpty()) {
            // Shift the load: plan without the nodes likely to breach when the others can still serve every line.
            List<Candidate> rest = cands.stream().filter(c -> !loadShift.containsKey(c.nodeId())).toList();
            if (!rest.isEmpty()) {
                plans = build(req, rest, card, softMap, now, penalties, calibration, soft);
                if (plans.stream().allMatch(p -> !p.unsourcedLineIds().isEmpty())) plans = List.of();
            }
        }
        if (plans.isEmpty()) plans = build(req, cands, card, softMap, now, penalties, calibration, soft);
        if (!loadShift.isEmpty()) plans = plans.stream().map(p -> p.withExplain("loadShift", loadShift)).toList();
        return new Run(order, req, cands, soft, plans, loadShift, fulfillmentGroupId);
    }

    private List<SourcingPlan> build(SourcingRequest req, List<Candidate> cands, RateCard card, Map<String, Double> softMap, Instant now, PenaltyLookup penalties,
                                     Map<String, Double> calibration, SoftScorer.SoftScores soft) {
        BuildContext ctx = new BuildContext(req, cands, card, softMap, now, now.atZone(ZoneOffset.UTC).toLocalDate(), settings.maxSplitPackages(), settings.businessDaysOnly(),
            settings.splitPenaltyUsd(card.splitPenaltyUsd()), settings.marginFloor(), settings.pickupRadiusKm(), settings.weights(), penalties, calibration);
        return planBuilder.build(ctx).stream().map(p -> p.withExplain("softScoring", soft.explain())).toList();
    }

    /**
     * FS-05: nodes whose learned backlog-to-breach curve, read at their current backlog, says a promise breach is at
     * least {@code sourcing.breach_shift_threshold} likely (default 0.5). Plans are built without them whenever the
     * other nodes can still serve every line, so load moves to nodes with headroom before the breach happens; when only
     * they can serve, they are used with the breach probability as an extra penalty on their on-time odds. Only while
     * {@code sourcing.node_backlog_breach} is promoted for live use.
     */
    Map<String, Double> breachPenalties(List<Candidate> cands) {
        Map<String, Double> out = new LinkedHashMap<>();
        try {
            if (!parameters.isLive(NodeReliabilityFeatures.BACKLOG_BREACH)) return out;
            double threshold = config.getDouble(BREACH_SHIFT_THRESHOLD, 0.5);
            for (Candidate c : cands) {
                var curve = facts.active(SubjectType.NODE, c.nodeId(), FactKey.NODE_BACKLOG_BREACH_CURVE);
                if (curve.isEmpty()) continue;
                var p = BacklogBreachCurve.at(BacklogBreachCurve.parse(curve.get()), c.node().backlogUnits());
                if (p.isEmpty()) continue;
                Double served = parameters.explain(NodeReliabilityFeatures.BACKLOG_BREACH, null, p.get()).value();
                if (served != null && served >= threshold) out.put(c.nodeId(), Math.round(Math.min(0.9, served) * 10_000) / 10_000d);
            }
        } catch (RuntimeException e) {
            log.debug("Backlog breach load shift skipped: {}", e.toString());
        }
        return out;
    }

    private SourcingResult sourceInternal(Order order, Set<String> excluded, Actor actor, List<String> reasons, String fulfillmentGroupId) {
        Run run = run(order, excluded, actor, Mode.LIVE, fulfillmentGroupId);
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
        BanditCall banditCall = new BanditCall();
        String policy = resolvePolicy(order, run, reasons, actor, banditCall);
        String requested = policy;
        String overrideReason = null;
        SourcingPlan chosen = bestFor(run.plans(), policy);
        if (chosen == null) {
            for (String p : FALLBACK_ORDER) { chosen = bestFor(run.plans(), p); if (chosen != null) { reasons.add("no " + policy + " plan is feasible; fell back to " + p); overrideReason = "no feasible " + policy + " plan"; policy = p; break; } }
        }
        if (chosen == null) chosen = run.plans().get(0);
        if (!run.loadShift().isEmpty()) reasons.add("load shifted away from nodes likely to breach at their current backlog: " + run.loadShift());
        chosen = agedStockTieBreak(run, chosen, reasons);
        if (chosen.packageCount() > 1) {
            SourcingPlan before = chosen;
            chosen = splitAcceptability(order, run, chosen, actor, reasons);
            if (!chosen.policy().equals(before.policy())) overrideReason = "split_acceptability preferred " + chosen.policy();
        }
        logBandit(order, banditCall, chosen.policy(), chosen.policy().equals(requested) ? null : overrideReason, actor, reasons);
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
        Map<String, String> categoryByLine = new HashMap<>();
        for (SourcingRequest.RequestLine l : run.req().lines()) categoryByLine.put(l.lineId(), category(l));
        Map<String, Integer> backlogByNode = new HashMap<>();
        Map<String, Candidate> candidateByNode = new HashMap<>();
        for (Candidate c : run.candidates()) { backlogByNode.put(c.nodeId(), c.node().backlogUnits()); candidateByNode.put(c.nodeId(), c); }
        Map<String, Double> baseByNode = new HashMap<>();
        for (Map<String, Object> ex : nodeExplains) if (ex.get("nodeId") != null && ex.get("onTimeBase") instanceof Number n) baseByNode.put(String.valueOf(ex.get("nodeId")), n.doubleValue());
        payload.put("packages", plan.packages().stream().map(pk -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nodeId", pk.nodeId());
            m.put("allocations", pk.allocations());
            m.put("eta", String.valueOf(pk.eta()));
            m.put("onTime", pk.onTimeProbability());
            m.put("cost", pk.totalCost().amount());
            m.put("skuClass", dominantCategory(pk, categoryByLine));
            if (backlogByNode.containsKey(pk.nodeId())) m.put("backlogUnits", backlogByNode.get(pk.nodeId()));
            if (baseByNode.containsKey(pk.nodeId())) m.put("onTimeBase", baseByNode.get(pk.nodeId()));
            Candidate cand = candidateByNode.get(pk.nodeId());
            if (cand != null) {
                // FS-02: the date the package must leave the node (today in its zone, or tomorrow past its cutoff), and the
                // day part on the tenant's clock, the same one the decision read its node facts under.
                m.put("shipBy", String.valueOf(Transit.today(cand.node(), now).plusDays(Transit.cutoffDelayDays(cand.node(), now))));
                m.put("dayPart", ai.intentoms.learning.model.DayPart.of(now, config));
            }
            return m;
        }).toList());
        String zip3 = LaneDeliveryLearner.zip3(run.req().destination().zip());
        if (zip3 != null) payload.put("destinationZip3", zip3);
        payload.put("shipMethod", Policies.PICKUP_CONSOLIDATED.equals(plan.policy()) ? SourcingRequest.METHOD_PICKUP : run.req().shipMethod());
        payload.put("totalCost", plan.totalCost().amount());
        payload.put("margin", plan.pnl().marginRatio());
        payload.put("contributionMargin", plan.pnl().contributionMargin());
        payload.put("meetsFloor", plan.meetsMarginFloor());
        payload.put("decisionRecordId", recordId == null ? "" : recordId);
        // OC-02: every decision that shaped this plan, so the groups it creates carry their lineage (GroupLineage).
        List<String> planners = new ArrayList<>();
        if (recordId != null) planners.add(recordId);
        if (plan.explain().get("splitAcceptability") instanceof Map<?, ?> split && split.get("recordId") != null && !String.valueOf(split.get("recordId")).isBlank())
            planners.add(String.valueOf(split.get("recordId")));
        payload.put("decisionRecordIds", planners);
        payload.put("softScores", run.soft().byNode());
        payload.put("score", plan.score());
        payload.put("unsourcedLineIds", plan.unsourcedLineIds());
        payload.put("fulfilmentGroupIds", groups.stream().filter(g -> plan.id().equals(g.sourcingPlanId())).map(FulfilmentGroup::id).toList());
        events.append("order", order.id(), new NewEvent(EVT_SOURCED, payload, actor).withCorrelation(order.id(), recordId));
    }

    // ---- policy ---------------------------------------------------------------------------------------------------

    String resolvePolicy(Order order, Run run, List<String> reasons, Actor actor, BanditCall banditCall) {
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
        return applyBandit(order, run, policy, reasons, banditCall);
    }

    /** FS-01: draws among the policies that clear the margin floor, keep the promise and fit the order type, and only
     * overrides the policy above when the bandit is promoted for live use (see {@link SourcingPolicyBandit}); otherwise
     * it is pure observation and this method returns the default policy unchanged. The draw is logged by
     * {@link #logBandit} once the policy finally used is known. */
    private String applyBandit(Order order, Run run, String defaultPolicy, List<String> reasons, BanditCall call) {
        List<String> eligible = banditArms(run.plans(), defaultPolicy, config.getInt(BANDIT_MAX_ETA_SLIP_DAYS, 0), run.req().isPickup());
        if (eligible.isEmpty()) return defaultPolicy;
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("channel", order.channel());
        context.put("intentPolicy", order.intent() == null ? null : order.intent().policy());
        context.put("customerTier", order.customer() == null ? null : order.customer().tier());
        context.put("lineCount", order.lines() == null ? 0 : order.lines().size());
        context.put("basketUnits", run.req().lines().stream().mapToInt(SourcingRequest.RequestLine::qty).sum());
        context.put("destinationZip", run.req().destination().zip());
        context.put("destinationState", run.req().destination().state());
        context.put("segment", segmentOf(order));
        context.put("networkLoad", SourcingPolicyBandit.loadBand(utilisation(run.candidates())));
        try {
            call.draw = bandit.draw(eligible, defaultPolicy, context);
            String applied = call.draw.applied();
            if (call.draw.acted() && applied != null && !applied.equals(defaultPolicy) && eligible.contains(applied)) {
                reasons.add("sourcing_policy_bandit chose " + applied + " over " + defaultPolicy + " (propensity " + call.draw.sampledPropensity() + ")");
                return applied;
            }
        } catch (RuntimeException e) {
            log.warn("Sourcing policy bandit unavailable for order {}: {}", order.id(), e.getMessage());
        }
        return defaultPolicy;
    }

    /** Logs the bandit draw with the policy finally used (OC-01: the logged arm is what the customer got). */
    private void logBandit(Order order, BanditCall call, String finalPolicy, String overrideReason, Actor actor, List<String> reasons) {
        if (call.draw == null) return;
        try {
            SourcingPolicyBandit.Choice c = bandit.log(order.id(), call.draw, finalPolicy, overrideReason, actor.ref());
            if (call.draw.acted()) reasons.add("bandit record " + c.decisionRecordId() + (overrideReason == null ? "" : " (overridden: " + overrideReason + ")"));
        } catch (RuntimeException e) {
            log.warn("Sourcing policy bandit could not log order {}: {}", order.id(), e.getMessage());
        }
    }

    private ai.intentoms.learning.store.CustomerSubjects customerSubjects;

    /** NF-07: customer facts are keyed by the customer's vault token. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCustomerSubjects(ai.intentoms.learning.store.CustomerSubjects customerSubjects) { this.customerSubjects = customerSubjects; }

    /** The customer's discovered segment (CI-02 {@code SEGMENT_MEMBERSHIP}), null when unknown. */
    private String segmentOf(Order order) {
        String ref = order.customerRef() != null ? order.customerRef() : order.customer() == null ? null : order.customer().ref();
        if (ref == null || ref.isBlank()) return null;
        try {
            return facts.active(SubjectType.CUSTOMER, customerSubjects == null ? ref : customerSubjects.key(ref), FactKey.SEGMENT_MEMBERSHIP)
                .map(f -> f.value() instanceof TypedValue.TextValue t ? t.value() : null).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Share of the candidates' daily capacity already queued as backlog; NaN when no candidate declares capacity. */
    static double utilisation(List<Candidate> cands) {
        double backlog = 0, capacity = 0;
        for (Candidate c : cands) {
            if (c.node().dailyCapacity() <= 0) continue;
            backlog += Math.max(0, c.node().backlogUnits());
            capacity += c.node().dailyCapacity();
        }
        return capacity <= 0 ? Double.NaN : backlog / capacity;
    }

    static List<String> banditArms(List<SourcingPlan> plans, String defaultPolicy, int maxSlipDays) { return banditArms(plans, defaultPolicy, maxSlipDays, false); }

    /**
     * FS-01 arms: the best plan of each policy that clears the margin floor, would not deliver later than the default
     * policy's best plan (plus {@code sourcing.bandit.max_eta_slip_days}) and fits the order type: a ship-to order is
     * never offered store pickup. Exploration never trades the promise, the margin or the delivery address for
     * information.
     */
    static List<String> banditArms(List<SourcingPlan> plans, String defaultPolicy, int maxSlipDays, boolean pickupOrder) {
        List<SourcingPlan> best = bestPerPolicy(plans);
        SourcingPlan reference = bestFor(plans, defaultPolicy);
        java.time.LocalDate latest = reference == null || reference.latestEta() == null ? null : reference.latestEta().plusDays(Math.max(0, maxSlipDays));
        return best.stream().filter(SourcingPlan::meetsMarginFloor)
            .filter(p -> pickupOrder == Policies.PICKUP_CONSOLIDATED.equals(p.policy()))
            .filter(p -> latest == null || p.latestEta() == null || !p.latestEta().isAfter(latest))
            .map(SourcingPlan::policy).toList();
    }

    /**
     * FS-02 / FS-06: whether a re-sourcing reason means the node refused or failed the work (a store reject, a short pick,
     * a timeout, an operator reporting a reject). Not a refusal: {@value #RESERVATION_SHORTAGE}, the OMS's own reservation
     * losing a race for the last units another order took between sourcing and reserving (stale ATP inside the OMS);
     * {@code reship}, a parcel lost after it left; {@code autonomy_undo:*}, an automatic action being undone. Those
     * re-source away from the node without penalising it, and {@code order.resourced} says {@code nodeRejected: false},
     * so the node reliability learner does not count them as rejects.
     */
    public static boolean nodeRefused(String reason) {
        if (reason == null) return true;
        String r = reason.trim().toLowerCase(java.util.Locale.ROOT);
        return !(r.equals(RESERVATION_SHORTAGE) || r.equals("reship") || r.startsWith("autonomy_undo"));
    }

    /** FS-06: the penalty a reject at this node earns, learned per node once promoted, else {@code sourcing.reject_penalty}. */
    double rejectPenalty(String nodeId) {
        double fallback = settings.rejectPenalty();
        try {
            Double learned = facts.active(SubjectType.NODE, nodeId, FactKey.NODE_REJECT_PENALTY)
                .filter(f -> f.value() instanceof TypedValue.NumberValue).map(f -> ((TypedValue.NumberValue) f.value()).value()).orElse(null);
            double v = parameters.resolve(NodeReliabilityLearner.REJECT_PENALTY_PARAMETER, fallback, learned);
            return v > 0 ? v : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /**
     * IU-03: when another plan of the same policy costs within the tolerance of the chosen one, keeps the margin floor
     * and delivers no later, prefer the plan that ships the oldest stock (unit-weighted {@code STOCK_AGE_DAYS} per SKU
     * at node). The tolerance is the reward's own trade-off: {@code sourcing.aged_stock_tiebreak_cost_pct} (default 3%)
     * scaled by the live objective's aged-stock weight relative to its cost weight, so a tenant that values aged-stock
     * relief more pays a little more to sell old stock, and one that sets the weight to 0 never does. Only while
     * {@code sourcing.aged_stock_tiebreak} is promoted for live use.
     */
    private SourcingPlan agedStockTieBreak(Run run, SourcingPlan chosen, List<String> reasons) {
        try {
            if (!parameters.isLive(AGED_STOCK_PARAMETER)) return chosen;
            double pct = agedStockTolerance();
            if (pct <= 0) return chosen;
            double chosenCost = chosen.totalCost().amount().doubleValue();
            double chosenAge = stockAge(chosen);
            SourcingPlan best = chosen;
            double bestAge = chosenAge;
            for (SourcingPlan p : run.plans()) {
                if (p == chosen || !p.policy().equals(chosen.policy())) continue;
                if (chosen.meetsMarginFloor() && !p.meetsMarginFloor()) continue;
                if (chosen.latestEta() != null && p.latestEta() != null && p.latestEta().isAfter(chosen.latestEta())) continue;
                if (p.totalCost().amount().doubleValue() > chosenCost * (1 + pct)) continue;
                double age = stockAge(p);
                if (age > bestAge + 1.0) { best = p; bestAge = age; }
            }
            if (best == chosen) return chosen;
            reasons.add(String.format(java.util.Locale.ROOT, "aged-stock tie-break: plan %s ships stock %.0f days old vs %.0f at about the same cost", best.id(), bestAge, chosenAge));
            return best.withExplain("agedStockTieBreak", Map.of("replacedPlanId", chosen.id(), "stockAgeDays", bestAge, "replacedStockAgeDays", chosenAge, "costTolerance", pct));
        } catch (RuntimeException e) {
            log.debug("Aged-stock tie-break skipped: {}", e.toString());
            return chosen;
        }
    }

    /** IU-03 cost tolerance: the configured base scaled by the objective's aged-stock weight over its cost weight. */
    double agedStockTolerance() {
        double base = config.getDouble(AGED_STOCK_COST_PCT, 0.03);
        try {
            var w = objectives.live().map(p -> p.weights()).orElse(ai.intentoms.learning.model.ObjectiveWeights.preset("balanced"));
            double ratio = w.cost() <= 0 ? w.inv() : w.inv() / w.cost();
            return Math.max(0.0, base * ratio);
        } catch (RuntimeException e) {
            return base;
        }
    }

    private double stockAge(SourcingPlan plan) {
        double sum = 0;
        int units = 0;
        for (PlanPackage pk : plan.packages()) {
            for (Allocation a : pk.allocations()) {
                double age = facts.active(SubjectType.SKU_NODE, a.sku() + "|" + pk.nodeId(), FactKey.STOCK_AGE_DAYS)
                    .filter(f -> f.value() instanceof TypedValue.NumberValue).map(f -> ((TypedValue.NumberValue) f.value()).value()).orElse(0.0);
                sum += age * a.qty();
                units += a.qty();
            }
        }
        return units == 0 ? 0 : sum / units;
    }

    static String category(SourcingRequest.RequestLine l) {
        Object c = l.attributes().get("category");
        return c == null || String.valueOf(c).isBlank() ? "default" : String.valueOf(c).toLowerCase(java.util.Locale.ROOT);
    }

    /** The SKU class carrying most units in the package (FS-02). */
    static String dominantCategory(PlanPackage pk, Map<String, String> categoryByLine) {
        Map<String, Integer> units = new HashMap<>();
        for (Allocation a : pk.allocations()) units.merge(categoryByLine.getOrDefault(a.lineId(), "default"), a.qty(), Integer::sum);
        return units.entrySet().stream().max(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey())).map(Map.Entry::getKey).orElse("default");
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
        ai.intentoms.platform.pii.KnownPii known = ai.intentoms.order.model.OrderPii.known(order); // NF-07: names and street addresses too
        o.put("notes", order.notes() == null ? "" : String.join(" ", order.notes().stream().map(Note::text).filter(t -> t != null).map(t -> ai.intentoms.platform.pii.FreeTextRedactor.redact(t, known)).toList()));
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
        if (run.fulfillmentGroupId() != null) input.put("fulfillmentGroupId", run.fulfillmentGroupId()); // OC-02: re-sourcing one group
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
