package ai.intentoms.simulation.margin;

import ai.intentoms.decision.model.Answer;
import ai.intentoms.decision.model.Band;
import ai.intentoms.decision.model.DecisionOptions;
import ai.intentoms.decision.model.DecisionResult;
import ai.intentoms.decision.model.Layer;
import ai.intentoms.decision.model.Mode;
import ai.intentoms.decision.runtime.DecisionService;
import ai.intentoms.finance.model.Money;
import ai.intentoms.finance.pnl.Pnl;
import ai.intentoms.inventory.model.Atp;
import ai.intentoms.inventory.service.InventoryService;
import ai.intentoms.order.capture.CatalogService;
import ai.intentoms.order.model.CatalogItem;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.simulation.margin.IntentDetector.DetectedIntent;
import ai.intentoms.sourcing.NodeRegistry;
import ai.intentoms.sourcing.candidate.DefaultCandidateGenerator;
import ai.intentoms.sourcing.config.SourcingSettings;
import ai.intentoms.sourcing.economics.EconomicsCalculator;
import ai.intentoms.sourcing.economics.EconomicsCalculator.LineAlloc;
import ai.intentoms.sourcing.economics.EconomicsCalculator.PackageEconomics;
import ai.intentoms.sourcing.model.Allocation;
import ai.intentoms.sourcing.model.Candidate;
import ai.intentoms.sourcing.model.Node;
import ai.intentoms.sourcing.model.PlanPackage;
import ai.intentoms.sourcing.model.Policies;
import ai.intentoms.sourcing.model.SourcingPlan;
import ai.intentoms.sourcing.model.SourcingRequest;
import ai.intentoms.sourcing.model.SourcingRequest.RequestLine;
import ai.intentoms.sourcing.plan.BuildContext;
import ai.intentoms.sourcing.plan.PlanBuilder;
import ai.intentoms.sourcing.rates.RateCard;
import ai.intentoms.sourcing.rates.RateCardRepository;
import ai.intentoms.sourcing.rates.Transit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Computes the two arms of a Margin Replay for one imported order, with nothing persisted as an order:
 * <ul>
 *   <li><b>Baseline</b> = the conventional OMS: ship-as-available from the nearest node (by great-circle distance) that is
 *       capable and stocked, or everything from one DC ({@code single_dc:<nodeId>}); no margin floor, no split penalty in
 *       the choice, no intent handling.</li>
 *   <li><b>Intent OMS</b> = the tenant's real sourcing policy: the same candidates through {@link PlanBuilder} with the
 *       tenant's weights, split penalty, margin floor and policies, the policy chosen from the detected fulfilment intent
 *       (the {@code fulfilment_intent} decision in SIMULATION mode, else keywords) with the channel default as fallback.</li>
 * </ul>
 * Both arms are costed by the same {@link EconomicsCalculator} against the tenant's live rate card and nodes, with the
 * order's own {@code placedAt} as "now" (cutoffs, ETAs). With {@code assumeStock} every active node covers every SKU;
 * otherwise coverage comes from live ATP. Learned node penalties apply to the Intent arm only (a conventional OMS does
 * not learn). Soft scores ({@code sourcing_node_score}) are neutral in both arms so the run stays fast and deterministic.
 */
@Component
public class ReplayPlanner {
    private static final Logger log = LoggerFactory.getLogger(ReplayPlanner.class);
    public static final String DECISION = "fulfilment_intent";
    public static final String SUBJECT_TYPE = "margin_replay";
    public static final String CHANNEL = "web";
    private static final List<String> FALLBACK_ORDER = List.of(Policies.SHIP_COMPLETE, Policies.SHIP_AS_AVAILABLE, Policies.SPLIT_UP_TO_K, Policies.EARLIEST_PARTIAL, Policies.PICKUP_CONSOLIDATED);

    /** One arm's outcome. {@code shipping} is the carrier cost alone; {@code fulfilmentCost} adds labour, packaging, handling, landed cost, markdown risk and the split penalty. */
    public record Arm(SourcingPlan plan, double shipping, double fulfilmentCost, Pnl pnl, double onTime, int packages, List<String> nodeIds, boolean meetsFloor, String policy, String reason) {
        public double marginUsd() { return pnl.contributionMargin().doubleValue(); }
        public double marginRatio() { return pnl.marginRatio(); }
        public boolean split() { return packages > 1; }
    }

    /** Both arms for one order; {@code error} set (and arms null) when no node could serve the order at all. */
    public record Result(ImportedOrder order, UsGeocoder.Location geo, SourcingRequest req, DetectedIntent intent, Arm baseline, Arm intentArm, int unknownSkus, boolean poBox,
                         String error, double revenue, int lines, int units, boolean hazmat) {
        public boolean planned() { return baseline != null && intentArm != null; }
        public boolean changed() {
            if (!planned()) return false;
            return !baseline.nodeIds().equals(intentArm.nodeIds()) || baseline.packages() != intentArm.packages() || Math.abs(baseline.fulfilmentCost() - intentArm.fulfilmentCost()) > 0.005;
        }
    }

    private final CatalogService catalog;
    private final NodeRegistry nodes;
    private final InventoryService inventory;
    private final RateCardRepository rates;
    private final SourcingSettings settings;
    private final PlanBuilder planBuilder;
    private final EconomicsCalculator economics;
    private final DecisionService decisions;

    public ReplayPlanner(CatalogService catalog, NodeRegistry nodes, InventoryService inventory, RateCardRepository rates, SourcingSettings settings, PlanBuilder planBuilder,
                         EconomicsCalculator economics, DecisionService decisions) {
        this.catalog = catalog;
        this.nodes = nodes;
        this.inventory = inventory;
        this.rates = rates;
        this.settings = settings;
        this.planBuilder = planBuilder;
        this.economics = economics;
        this.decisions = decisions;
    }

    public Result replay(ImportedOrder o, UsGeocoder.Location geo, MarginReplayOptions opts, String jobId, Instant fallbackNow) {
        Instant now = o.placedAt() == null ? fallbackNow : o.placedAt();
        RateCard card = rates.current();
        int[] unknown = {0};
        SourcingRequest req = request(o, geo, opts, unknown);
        double revenue = o.merchandise().add(o.shippingCharged()).doubleValue();
        boolean hazmat = req.hasHazmat();
        if (req.lines().isEmpty()) return new Result(o, geo, req, DetectedIntent.none(), null, null, unknown[0], o.poBox(), "no shippable (non-digital) lines", revenue, 0, 0, hazmat);

        DetectedIntent intent = intent(o, req, opts, jobId);
        List<Candidate> cands = candidates(req, geo, now, opts.assumeStock(), card);
        if (cands.isEmpty()) {
            String why = req.isPickup() ? "no pickup-capable store near the destination" : opts.assumeStock() ? "no active node is capable of shipping this order" + (hazmat ? " (hazmat)" : "") : "no node holds stock for any line (assumeStock=false)";
            return new Result(o, geo, req, intent, null, null, unknown[0], o.poBox(), why, revenue, req.lines().size(), req.totalUnits(), hazmat);
        }
        double floor = settings.marginFloor();
        double splitPenalty = settings.splitPenaltyUsd(card.splitPenaltyUsd());
        boolean businessDays = settings.businessDaysOnly();
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();

        Arm baseline = baseline(req, cands, card, opts, today, businessDays, splitPenalty, floor);
        Arm intentArm = intentArm(req, cands, card, intent, now, today, businessDays, splitPenalty, floor);
        String error = baseline == null && intentArm == null ? "no node can serve any line" : baseline == null ? "baseline could not place the order" : intentArm == null ? "Intent OMS found no feasible plan" : null;
        return new Result(o, geo, req, intent, baseline, intentArm, unknown[0], o.poBox(), error, revenue, req.lines().size(), req.totalUnits(), hazmat);
    }

    // ---- request ----------------------------------------------------------------------------------------------------

    SourcingRequest request(ImportedOrder o, UsGeocoder.Location geo, MarginReplayOptions opts, int[] unknownCount) {
        List<RequestLine> lines = new ArrayList<>(o.lines().size());
        int i = 0;
        for (ImportedOrder.Line l : o.lines()) {
            i++;
            Optional<CatalogItem> item = l.skuMissing() ? Optional.empty() : catalog.find(l.sku());
            Map<String, Object> attrs = new LinkedHashMap<>();
            if (item.isPresent()) {
                if (item.get().flag("digital")) continue;
                attrs.putAll(item.get().lineAttributes());
            } else {
                unknownCount[0]++;
                attrs.put("cogs", l.unitPrice().multiply(BigDecimal.valueOf(opts.unknownSkuCogsRatio())).setScale(2, RoundingMode.HALF_UP));
                attrs.put("weight_kg", 0.5);
                attrs.put("unknown_sku", true);
            }
            lines.add(new RequestLine("L" + i, l.sku(), l.qty(), Money.of(l.unitPrice(), "USD"), null, attrs));
        }
        SourcingRequest.Destination dest = new SourcingRequest.Destination(o.zip(), geo.state(), "US");
        SourcingRequest.Constraints constraints = SourcingRequest.Constraints.fromMap(settings.orderTypeConstraints("standard_ship"));
        return new SourcingRequest(o.orderId(), lines, dest, shipMethod(o.shipMethod()), "standard_ship", constraints, Money.of(o.shippingCharged(), "USD"));
    }

    /** Maps a retailer's shipping-method label onto the rate card services (ground | two_day | next_day | same_day | pickup). */
    public static String shipMethod(String raw) {
        if (raw == null || raw.isBlank()) return SourcingRequest.METHOD_GROUND;
        String m = raw.toLowerCase(Locale.ROOT);
        if (m.contains("pickup") || m.contains("pick up") || m.contains("pick-up") || m.contains("collect")) return SourcingRequest.METHOD_PICKUP;
        if (m.contains("same day") || m.contains("same-day") || m.contains("sameday")) return SourcingRequest.METHOD_SAME_DAY;
        if (m.contains("overnight") || m.contains("next day") || m.contains("next-day") || m.contains("1 day") || m.contains("one day")) return "next_day";
        if (m.contains("express") || m.contains("expedited") || m.contains("2 day") || m.contains("2-day") || m.contains("two day") || m.contains("two-day") || m.contains("priority") || m.contains("second day")) return "two_day";
        return SourcingRequest.METHOD_GROUND;
    }

    // ---- intent -----------------------------------------------------------------------------------------------------

    DetectedIntent intent(ImportedOrder o, SourcingRequest req, MarginReplayOptions opts, String jobId) {
        if (!o.hasNote()) return DetectedIntent.none();
        if (!opts.useDecisions()) return IntentDetector.fromKeywords(o.note());
        try {
            Map<String, Object> order = new LinkedHashMap<>();
            order.put("id", o.orderId());
            order.put("channel", CHANNEL);
            order.put("notes", List.of(o.note()));
            order.put("intent_choices", List.of());
            order.put("has_hazmat", req.hasHazmat());
            order.put("order_type", "standard_ship");
            order.put("line_count", req.lines().size());
            Map<String, Object> customer = new LinkedHashMap<>();
            customer.put("tier", o.customerTier() == null ? "standard" : o.customerTier());
            customer.put("history", Map.of());
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("order", order);
            input.put("customer", customer);
            DecisionOptions dopts = new DecisionOptions(SUBJECT_TYPE, jobId + ":" + o.orderId(), jobId, Mode.SIMULATION, null, Actor.system("margin-replay"), Map.of());
            DecisionResult r = decisions.decide(DECISION, input, dopts);
            String value = r.value() == null ? null : String.valueOf(r.value());
            boolean usable = r.layer() != Layer.DEFAULT && (r.band() == Band.ACT || r.band() == Band.CONFIRM);
            String policy = usable && Policies.known(value) ? value : null;
            Boolean splitOk = noul(r.answers().get("split_ok"));
            Boolean gift = noul(r.answers().get("is_gift"));
            boolean isGift = Boolean.TRUE.equals(gift);
            if (policy == null && !isGift && splitOk == null) {
                DetectedIntent kw = IntentDetector.fromKeywords(o.note());
                return kw.any() ? kw : DetectedIntent.none().withDecision(r.band() == null ? null : r.band().name(), r.recordId(), r.confidence());
            }
            return new DetectedIntent(policy, isGift, Policies.EARLIEST_PARTIAL.equals(policy), Policies.SHIP_COMPLETE.equals(policy), Policies.PICKUP_CONSOLIDATED.equals(policy), splitOk,
                "decision", r.confidence(), r.band() == null ? null : r.band().name(), r.recordId(), IntentDetector.label(policy, isGift));
        } catch (RuntimeException e) {
            log.debug("fulfilment_intent unavailable for replay order {}: {}", o.orderId(), e.getMessage());
            return IntentDetector.fromKeywords(o.note());
        }
    }

    private static Boolean noul(Answer a) {
        if (a == null) return null;
        if (a.value() instanceof Boolean b) return b;
        double d = a.asDouble();
        if (Double.isNaN(d) || Math.abs(d - 0.5) < 1e-9) return null;
        return d > 0.5;
    }

    // ---- candidates -------------------------------------------------------------------------------------------------

    List<Candidate> candidates(SourcingRequest req, UsGeocoder.Location geo, Instant now, boolean assumeStock, RateCard card) {
        Set<String> required = DefaultCandidateGenerator.requiredCapabilities(req);
        Map<String, Integer> wanted = req.unitsBySku();
        SourcingRequest.Constraints c = req.constraints();
        Map<String, Map<String, Integer>> availBySku = new HashMap<>();
        if (!assumeStock) {
            for (String sku : wanted.keySet()) {
                Map<String, Integer> perNode = new HashMap<>();
                for (Atp a : inventory.atp(sku)) if (a.available() > 0) perNode.put(a.nodeId(), a.available());
                availBySku.put(sku, perNode);
            }
        }
        List<Candidate> out = new ArrayList<>();
        for (Node node : nodes.active()) {
            if (c.excludedNodes().contains(node.id())) continue;
            if (!c.nodeTypes().isEmpty() && !c.nodeTypes().contains(node.type())) continue;
            if (!node.capabilities().containsAll(required)) continue;
            if (!req.isPickup() && !node.canShip()) continue;
            Map<String, Integer> coverage = new TreeMap<>();
            boolean complete = true;
            for (var e : wanted.entrySet()) {
                int avail = assumeStock ? e.getValue() : availBySku.getOrDefault(e.getKey(), Map.of()).getOrDefault(node.id(), 0);
                int q = Math.min(avail, e.getValue());
                if (q > 0) coverage.put(e.getKey(), q);
                if (q < e.getValue()) complete = false;
            }
            if (coverage.isEmpty()) continue;
            double distance = geo.distanceKm(node.lat(), node.lon());
            if (c.maxDistanceKm() != null && distance > c.maxDistanceKm()) continue;
            if (req.isPickup() && c.maxDistanceKm() == null && distance > settings.pickupRadiusKm() * 4) continue;
            int transit = req.isPickup() ? 0 : card.transitDays(req.shipMethod(), distance);
            transit += Transit.cutoffDelayDays(node, now);
            out.add(new Candidate(node, coverage, complete, Math.round(distance * 10) / 10.0, transit));
        }
        out.sort(Comparator.comparing((Candidate x) -> !x.complete()).thenComparingDouble(Candidate::distanceKm).thenComparing(Candidate::nodeId));
        return out;
    }

    // ---- Intent OMS arm ---------------------------------------------------------------------------------------------

    Arm intentArm(SourcingRequest req, List<Candidate> cands, RateCard card, DetectedIntent intent, Instant now, LocalDate today, boolean businessDays, double splitPenalty, double floor) {
        BuildContext ctx = new BuildContext(req, cands, card, Map.of(), now, today, settings.maxSplitPackages(), businessDays, splitPenalty, floor, settings.pickupRadiusKm(), settings.weights(), nodes::penalty);
        List<SourcingPlan> plans = planBuilder.build(ctx);
        if (plans.isEmpty()) return null;
        List<String> reasons = new ArrayList<>();
        String policy;
        if (req.isPickup()) { policy = Policies.PICKUP_CONSOLIDATED; reasons.add("pickup order"); }
        else if (intent.policy() != null && Policies.known(intent.policy())) {
            policy = intent.policy();
            reasons.add("policy " + policy + " from intent (" + intent.source() + (intent.band() == null ? "" : ", band " + intent.band()) + ", confidence " + fmt(intent.confidence()) + ")");
        } else {
            String dflt = settings.channelDefaultPolicy(CHANNEL);
            policy = Policies.known(dflt) ? dflt : Policies.SHIP_AS_AVAILABLE;
            reasons.add("no intent; channel '" + CHANNEL + "' default " + policy);
        }
        if (Policies.splitting(policy) && Boolean.FALSE.equals(intent.splitOk()) && bestFor(plans, Policies.SHIP_COMPLETE) != null) {
            reasons.add("buyer would not accept a split; preferring ship_complete");
            policy = Policies.SHIP_COMPLETE;
        }
        SourcingPlan chosen = bestFor(plans, policy);
        if (chosen == null) {
            for (String p : FALLBACK_ORDER) { chosen = bestFor(plans, p); if (chosen != null) { reasons.add("no " + policy + " plan is feasible; fell back to " + p); policy = p; break; } }
        }
        if (chosen == null) chosen = plans.get(0);
        if (!chosen.meetsMarginFloor()) reasons.add(String.format(Locale.ROOT, "margin %.1f%% is below the %.0f%% floor: Intent OMS holds the order for approval", chosen.pnl().marginRatio() * 100, floor * 100));
        double shipping = 0;
        List<String> nodeIds = new ArrayList<>();
        for (PlanPackage pk : chosen.packages()) { shipping += pk.shippingCost().amount().doubleValue(); if (!nodeIds.contains(pk.nodeId())) nodeIds.add(pk.nodeId()); }
        nodeIds.sort(Comparator.naturalOrder());
        Object nodeExplain = chosen.explain().get("nodes");
        if (nodeExplain instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> m && m.get("reason") != null) reasons.add(String.valueOf(m.get("reason")));
        return new Arm(chosen, round2(shipping), round2(chosen.totalCost().amount().doubleValue()), chosen.pnl(), chosen.onTimeProbability(), chosen.packageCount(), nodeIds, chosen.meetsMarginFloor(),
            chosen.policy(), String.join("; ", reasons));
    }

    // ---- baseline arm -----------------------------------------------------------------------------------------------

    Arm baseline(SourcingRequest req, List<Candidate> cands, RateCard card, MarginReplayOptions opts, LocalDate today, boolean businessDays, double splitPenalty, double floor) {
        Map<String, Integer> remaining = new LinkedHashMap<>();
        for (RequestLine l : req.lines()) remaining.merge(l.lineId(), l.qty(), Integer::sum);
        record Pkg(Candidate c, List<LineAlloc> allocs) {}
        List<Pkg> packages = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        List<String> reasons = new ArrayList<>();
        String policy = MarginReplayOptions.NEAREST_NODE;
        if (opts.singleDc()) {
            policy = "single_dc";
            Candidate dc = cands.stream().filter(x -> x.nodeId().equalsIgnoreCase(opts.singleDcNode())).findFirst().orElse(null);
            if (dc != null && coverable(req, dc, remaining) > 0) {
                packages.add(new Pkg(dc, allocate(req, dc, remaining)));
                used.add(dc.nodeId());
                reasons.add("everything from " + dc.nodeId() + " (single-DC baseline)");
            } else reasons.add(opts.singleDcNode() + " cannot serve this order; nearest capable node used");
        }
        List<Candidate> byDistance = cands.stream().sorted(Comparator.comparingDouble(Candidate::distanceKm).thenComparing(Candidate::nodeId)).toList();
        while (!remaining.isEmpty()) {
            Candidate best = null;
            for (Candidate x : byDistance) { if (used.contains(x.nodeId())) continue; if (coverable(req, x, remaining) > 0) { best = x; break; } }
            if (best == null) break;
            packages.add(new Pkg(best, allocate(req, best, remaining)));
            used.add(best.nodeId());
            if (!opts.singleDc() || packages.size() > 1) reasons.add("nearest node with stock: " + best.nodeId() + " (" + fmt(best.distanceKm()) + " km)");
        }
        if (packages.isEmpty()) return null;
        String ccy = req.currency();
        List<PlanPackage> plan = new ArrayList<>(packages.size());
        List<PackageEconomics> econs = new ArrayList<>(packages.size());
        List<LineAlloc> all = new ArrayList<>();
        List<Map<String, Object>> explain = new ArrayList<>();
        Money total = EconomicsCalculator.splitPenalty(splitPenalty, packages.size(), ccy);
        double shipping = 0, onTime = 1.0;
        List<String> nodeIds = new ArrayList<>();
        for (Pkg p : packages) {
            PackageEconomics econ = economics.packageEconomics(card, p.c().node(), p.allocs(), req, p.c().distanceKm());
            List<Allocation> allocations = new ArrayList<>();
            for (LineAlloc a : p.allocs()) allocations.add(new Allocation(a.line().lineId(), a.line().sku(), a.qty()));
            double pOnTime = card.onTimeProbability(p.c().transitDays(), p.c().node().reliability());
            plan.add(new PlanPackage(p.c().nodeId(), allocations, Transit.eta(today, p.c().transitDays(), businessDays), round4(pOnTime), econ.shipping(), econ.labour(), econ.packaging(), econ.landed(),
                econ.handling(), econ.markdown(), p.c().transitDays(), p.c().distanceKm()));
            econs.add(econ);
            all.addAll(p.allocs());
            total = total.add(econ.total());
            shipping += econ.shipping().amount().doubleValue();
            onTime = Math.min(onTime, pOnTime);
            if (!nodeIds.contains(p.c().nodeId())) nodeIds.add(p.c().nodeId());
            Map<String, Object> ex = new LinkedHashMap<>();
            ex.put("nodeId", p.c().nodeId());
            ex.put("distanceKm", p.c().distanceKm());
            ex.put("transitDays", p.c().transitDays());
            ex.put("cost", econ.total().amount());
            explain.add(ex);
        }
        nodeIds.sort(Comparator.naturalOrder());
        Pnl pnl = economics.pnl(all, req.shippingRevenue(), econs, EconomicsCalculator.splitPenalty(splitPenalty, packages.size(), ccy));
        List<String> unsourced = new ArrayList<>(remaining.keySet());
        if (!unsourced.isEmpty()) reasons.add(unsourced.size() + " line(s) left unsourced");
        Map<String, Object> ex = new LinkedHashMap<>();
        ex.put("baseline", policy);
        ex.put("nodes", explain);
        SourcingPlan sp = new SourcingPlan(Ids.prefixed("base"), policy, plan, total, pnl, 0.0, pnl.meetsMarginFloor(floor), unsourced, ex);
        return new Arm(sp, round2(shipping), round2(total.amount().doubleValue()), pnl, round4(onTime), plan.size(), nodeIds, pnl.meetsMarginFloor(floor), policy, String.join("; ", reasons));
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    static SourcingPlan bestFor(List<SourcingPlan> plans, String policy) {
        return plans.stream().filter(p -> policy.equals(p.policy())).max(Comparator.comparingDouble(SourcingPlan::score).thenComparing(Comparator.comparing(SourcingPlan::id).reversed())).orElse(null);
    }

    static int coverable(SourcingRequest req, Candidate c, Map<String, Integer> remaining) {
        Map<String, Integer> cov = new HashMap<>(c.coverage());
        int units = 0;
        for (RequestLine l : req.lines()) {
            int need = remaining.getOrDefault(l.lineId(), 0);
            if (need == 0) continue;
            int q = Math.min(need, cov.getOrDefault(l.sku(), 0));
            if (q > 0) { units += q; cov.merge(l.sku(), -q, Integer::sum); }
        }
        return units;
    }

    static List<LineAlloc> allocate(SourcingRequest req, Candidate c, Map<String, Integer> remaining) {
        Map<String, Integer> cov = new HashMap<>(c.coverage());
        List<LineAlloc> out = new ArrayList<>();
        for (RequestLine l : req.lines()) {
            int need = remaining.getOrDefault(l.lineId(), 0);
            if (need == 0) continue;
            int q = Math.min(need, cov.getOrDefault(l.sku(), 0));
            if (q <= 0) continue;
            out.add(new LineAlloc(l, q));
            cov.merge(l.sku(), -q, Integer::sum);
            if (need - q <= 0) remaining.remove(l.lineId()); else remaining.put(l.lineId(), need - q);
        }
        return out;
    }

    static double round2(double v) { return Math.round(v * 100) / 100d; }
    static double round4(double v) { return Math.round(v * 10_000) / 10_000d; }
    private static String fmt(double v) { return String.format(Locale.ROOT, "%.2f", v); }
}
