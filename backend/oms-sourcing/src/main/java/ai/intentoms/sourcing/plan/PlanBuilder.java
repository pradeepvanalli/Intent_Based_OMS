package ai.intentoms.sourcing.plan;

import ai.intentoms.finance.model.Money;
import ai.intentoms.finance.pnl.Pnl;
import ai.intentoms.platform.ids.Ids;
import ai.intentoms.sourcing.economics.EconomicsCalculator;
import ai.intentoms.sourcing.economics.EconomicsCalculator.LineAlloc;
import ai.intentoms.sourcing.economics.EconomicsCalculator.PackageEconomics;
import ai.intentoms.sourcing.model.Allocation;
import ai.intentoms.sourcing.model.Candidate;
import ai.intentoms.sourcing.model.Node;
import ai.intentoms.sourcing.model.PlanPackage;
import ai.intentoms.sourcing.model.Policies;
import ai.intentoms.sourcing.model.SimulationContext;
import ai.intentoms.sourcing.model.SourcingPlan;
import ai.intentoms.sourcing.model.SourcingRequest;
import ai.intentoms.sourcing.model.SourcingRequest.RequestLine;
import ai.intentoms.sourcing.rates.Transit;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds scored {@link SourcingPlan}s for every applicable policy (FR-SRC-02, FR-SRC-03):
 * <ul>
 *   <li>{@code ship_complete}: one node covering everything (up to {@value #MAX_VARIANTS} variants, best node first).</li>
 *   <li>{@code ship_as_available}: greedy — the node covering the most remaining units (ties by node preference), then
 *       the next for the remainder, up to K packages; what is left is unsourced (a backorder when the order type allows).</li>
 *   <li>{@code split_up_to_k}: the greedy plan only when it covers everything within K packages.</li>
 *   <li>{@code pickup_consolidated}: a BOPIS store covering everything (any distance for pickup orders, within the pickup
 *       radius for ship-to-home orders so it can be offered as an alternative, FR-SRC-04).</li>
 *   <li>{@code earliest_partial}: the line marked {@code urgent} (else the first line) from the fastest node, the rest as
 *       available.</li>
 * </ul>
 * Node preference combines unit cost, on-time probability and the Jev soft score with the tenant's weights; every plan
 * explains why each node was picked (component scores included).
 */
@Service
public class PlanBuilder {
    /** Variants kept per multi-variant policy so the alternatives table stays bounded. */
    public static final int MAX_VARIANTS = 10;

    /** Per-candidate derived figures used to rank nodes within a policy. */
    record NodeEval(Candidate c, double onTime, double soft, double unitCost, double normUnitCost, double preference) {
        String id() { return c.nodeId(); }
        Node node() { return c.node(); }
    }

    private final EconomicsCalculator economics;

    public PlanBuilder(EconomicsCalculator economics) { this.economics = economics; }

    /** All plan variants for the request, scored and sorted best first. */
    public List<SourcingPlan> build(BuildContext ctx) {
        if (ctx.candidates().isEmpty() || ctx.req().lines().isEmpty()) return List.of();
        List<NodeEval> evals = evaluate(ctx);
        List<SourcingPlan> plans = new ArrayList<>();
        plans.addAll(shipComplete(ctx, evals));
        SourcingPlan asAvailable = greedy(ctx, evals, Policies.SHIP_AS_AVAILABLE, ctx.maxSplit(), false, null);
        if (asAvailable != null) plans.add(asAvailable);
        SourcingPlan splitK = greedy(ctx, evals, Policies.SPLIT_UP_TO_K, ctx.maxSplit(), true, null);
        if (splitK != null && splitK.packageCount() > 1) plans.add(splitK);
        plans.addAll(pickup(ctx, evals));
        SourcingPlan earliest = earliestPartial(ctx, evals);
        if (earliest != null) plans.add(earliest);
        List<SourcingPlan> scored = new ArrayList<>(PlanScorer.score(plans, ctx.weights(), ctx.maxSplit(), ctx.softScores()));
        scored.sort(Comparator.comparingDouble(SourcingPlan::score).reversed().thenComparing(SourcingPlan::policy).thenComparing(SourcingPlan::id));
        return scored;
    }

    // ---- node evaluation ------------------------------------------------------------------------------------------

    List<NodeEval> evaluate(BuildContext ctx) {
        SourcingRequest req = ctx.req();
        double weight = 0;
        for (RequestLine l : req.lines()) weight += l.weightKg() * l.qty();
        double minUnit = Double.MAX_VALUE, maxUnit = 0;
        List<double[]> raw = new ArrayList<>();
        for (Candidate c : ctx.candidates()) {
            int units = Math.max(1, c.coveredUnits());
            double share = weight * units / Math.max(1, req.totalUnits());
            double shipping = req.isPickup() ? 0 : ctx.card().quote(req.shipMethod(), c.distanceKm(), share).cost().amount().doubleValue();
            double unit = shipping / units + c.node().costNumber("labour_per_unit", 0) + c.node().costNumber("handling", 0) + c.node().costNumber("packaging", 0) / units;
            raw.add(new double[]{unit});
            minUnit = Math.min(minUnit, unit);
            maxUnit = Math.max(maxUnit, unit);
        }
        List<NodeEval> out = new ArrayList<>(ctx.candidates().size());
        for (int i = 0; i < ctx.candidates().size(); i++) {
            Candidate c = ctx.candidates().get(i);
            double unit = raw.get(i)[0];
            double norm = maxUnit - minUnit < 1e-9 ? 0.0 : (unit - minUnit) / (maxUnit - minUnit);
            double onTime = onTime(ctx, c, c.transitDays(), c.coverage().keySet());
            double soft = ctx.soft(c.nodeId());
            var w = ctx.weights();
            double pref = w.cost() * (1 - norm) + w.time() * onTime + w.soft() * soft;
            out.add(new NodeEval(c, onTime, soft, unit, norm, pref));
        }
        return out;
    }

    private double onTime(BuildContext ctx, Candidate c, int transitDays, Iterable<String> skus) {
        double base = ctx.card().onTimeProbability(transitDays, c.node().reliability());
        double penalty = ctx.penalties().forPackage(c.node(), skus);
        return clamp(base * (1 - penalty) + SimulationContext.current().onTimeDelta());
    }

    private static Comparator<NodeEval> byPreference() {
        return Comparator.comparingDouble(NodeEval::preference).reversed().thenComparingDouble(e -> e.c().distanceKm()).thenComparing(NodeEval::id);
    }

    // ---- policies -------------------------------------------------------------------------------------------------

    List<SourcingPlan> shipComplete(BuildContext ctx, List<NodeEval> evals) {
        List<NodeEval> complete = evals.stream().filter(e -> e.c().complete() && (ctx.req().isPickup() ? e.node().has(Node.CAP_BOPIS) : e.node().canShip())).sorted(byPreference()).toList();
        List<SourcingPlan> out = new ArrayList<>();
        int rank = 0;
        for (NodeEval e : complete) {
            if (out.size() >= MAX_VARIANTS) break;
            List<LineAlloc> allocs = new ArrayList<>();
            for (RequestLine l : ctx.req().lines()) allocs.add(new LineAlloc(l, l.qty()));
            PackageBuild pkg = makePackage(ctx, e, allocs, e.c().transitDays(), "covers every line; node preference rank " + (++rank));
            out.add(assemble(ctx, Policies.SHIP_COMPLETE, List.of(pkg), List.of(), Map.of("variant", rank)));
        }
        return out;
    }

    SourcingPlan greedy(BuildContext ctx, List<NodeEval> evals, String policy, int k, boolean requireComplete, NodeEval seed) {
        return greedy(ctx, evals, policy, k, requireComplete, seed, null);
    }

    private SourcingPlan greedy(BuildContext ctx, List<NodeEval> evals, String policy, int k, boolean requireComplete, NodeEval seed, Map<String, Integer> preAllocated) {
        SourcingRequest req = ctx.req();
        Map<String, Integer> remaining = new LinkedHashMap<>();
        for (RequestLine l : req.lines()) remaining.merge(l.lineId(), l.qty(), Integer::sum);
        if (preAllocated != null) preAllocated.forEach((line, q) -> remaining.computeIfPresent(line, (x, cur) -> Math.max(0, cur - q)));
        remaining.values().removeIf(q -> q <= 0);
        List<PackageBuild> packages = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        List<NodeEval> pool = evals.stream().filter(e -> req.isPickup() ? e.node().has(Node.CAP_BOPIS) : e.node().canShip()).toList();
        int step = 0;
        while (!remaining.isEmpty() && packages.size() < k) {
            NodeEval best = null;
            int bestUnits = 0;
            for (NodeEval e : pool) {
                if (used.contains(e.id())) continue;
                int units = coverable(req, e.c(), remaining);
                if (units == 0) continue;
                if (best == null || units > bestUnits || (units == bestUnits && byPreference().compare(e, best) < 0)) { best = e; bestUnits = units; }
            }
            if (best == null) break;
            List<LineAlloc> allocs = allocate(req, best.c(), remaining);
            used.add(best.id());
            step++;
            packages.add(makePackage(ctx, best, allocs, best.c().transitDays(), "step " + step + ": covers " + bestUnits + " of the remaining units; preference " + fmt(best.preference())));
        }
        if (packages.isEmpty()) return null;
        if (requireComplete && !remaining.isEmpty()) return null;
        List<String> unsourced = new ArrayList<>(remaining.keySet());
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("maxPackages", k);
        if (!unsourced.isEmpty()) extra.put("backorder", req.constraints().allowBackorder());
        return assemble(ctx, policy, packages, unsourced, extra);
    }

    List<SourcingPlan> pickup(BuildContext ctx, List<NodeEval> evals) {
        SourcingRequest req = ctx.req();
        List<NodeEval> stores = evals.stream()
            .filter(e -> e.node().has(Node.CAP_BOPIS) && e.c().complete() && (req.isPickup() || e.c().distanceKm() <= ctx.pickupRadiusKm()))
            .sorted(Comparator.comparingDouble((NodeEval e) -> e.c().distanceKm()).thenComparing(byPreference())).toList();
        List<SourcingPlan> out = new ArrayList<>();
        int rank = 0;
        for (NodeEval e : stores) {
            if (out.size() >= 5) break;
            List<LineAlloc> allocs = new ArrayList<>();
            for (RequestLine l : req.lines()) allocs.add(new LineAlloc(l, l.qty()));
            int transit = Transit.cutoffDelayDays(e.node(), ctx.now());
            SourcingRequest pickupReq = req.isPickup() ? req : new SourcingRequest(req.orderId(), req.lines(), req.destination(), SourcingRequest.METHOD_PICKUP, req.orderType(), req.constraints(), req.shippingRevenue());
            PackageBuild pkg = makePackage(ctx.withRequest(pickupReq), e, allocs, transit, "BOPIS store " + fmt(e.c().distanceKm()) + " km away covers every line; rank " + (++rank));
            out.add(assemble(ctx.withRequest(pickupReq), Policies.PICKUP_CONSOLIDATED, List.of(pkg), List.of(), Map.of("variant", rank, "distanceKm", e.c().distanceKm())));
        }
        return out;
    }

    SourcingPlan earliestPartial(BuildContext ctx, List<NodeEval> evals) {
        SourcingRequest req = ctx.req();
        if (req.lines().size() < 2 || req.isPickup()) return null;
        RequestLine urgent = req.lines().stream().filter(RequestLine::urgent).findFirst().orElse(req.lines().get(0));
        NodeEval fastest = evals.stream().filter(e -> e.node().canShip() && e.c().covered(urgent.sku()) >= urgent.qty())
            .min(Comparator.comparingInt((NodeEval e) -> e.c().transitDays()).thenComparing(byPreference())).orElse(null);
        if (fastest == null) return null;
        List<PackageBuild> packages = new ArrayList<>();
        packages.add(makePackage(ctx, fastest, List.of(new LineAlloc(urgent, urgent.qty())), fastest.c().transitDays(),
            "fastest node (" + fastest.c().transitDays() + "d) for " + (urgent.urgent() ? "the urgent line" : "the first line") + " " + urgent.lineId()));
        SourcingPlan rest = greedy(ctx, evals.stream().filter(e -> !e.id().equals(fastest.id())).toList(), Policies.EARLIEST_PARTIAL, Math.max(1, ctx.maxSplit() - 1), false, null, Map.of(urgent.lineId(), urgent.qty()));
        List<String> unsourced = new ArrayList<>();
        if (rest != null) {
            for (PlanPackage pk : rest.packages()) {
                NodeEval e = evals.stream().filter(x -> x.id().equals(pk.nodeId())).findFirst().orElseThrow();
                List<LineAlloc> allocs = new ArrayList<>();
                for (Allocation a : pk.allocations()) allocs.add(new LineAlloc(line(req, a.lineId()), a.qty()));
                packages.add(makePackage(ctx, e, allocs, pk.transitDays(), "remainder as available; preference " + fmt(e.preference())));
            }
            unsourced.addAll(rest.unsourcedLineIds());
        } else {
            for (RequestLine l : req.lines()) if (!l.lineId().equals(urgent.lineId())) unsourced.add(l.lineId());
        }
        return assemble(ctx, Policies.EARLIEST_PARTIAL, packages, unsourced, Map.of("urgentLineId", urgent.lineId()));
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    /** Units of {@code remaining} the candidate could still supply (coverage is per sku, shared by lines of that sku). */
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

    /** Allocates from the candidate in line order and decrements {@code remaining}. */
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

    private static RequestLine line(SourcingRequest req, String lineId) {
        return req.lines().stream().filter(l -> l.lineId().equals(lineId)).findFirst().orElseThrow();
    }

    record PackageBuild(PlanPackage pkg, PackageEconomics econ, List<LineAlloc> allocs, Map<String, Object> explain) {}

    private PackageBuild makePackage(BuildContext ctx, NodeEval e, List<LineAlloc> allocs, int transitDays, String reason) {
        PackageEconomics econ = economics.packageEconomics(ctx.card(), e.node(), allocs, ctx.req(), e.c().distanceKm());
        List<Allocation> allocations = new ArrayList<>(allocs.size());
        List<String> skus = new ArrayList<>(allocs.size());
        for (LineAlloc a : allocs) { allocations.add(new Allocation(a.line().lineId(), a.line().sku(), a.qty())); skus.add(a.line().sku()); }
        double onTime = onTime(ctx, e.c(), transitDays, skus);
        PlanPackage pkg = new PlanPackage(e.id(), allocations, Transit.eta(ctx.today(), transitDays, ctx.businessDaysOnly()), round(onTime), econ.shipping(), econ.labour(), econ.packaging(),
            econ.landed(), econ.handling(), econ.markdown(), transitDays, e.c().distanceKm());
        Map<String, Object> ex = new LinkedHashMap<>();
        ex.put("nodeId", e.id());
        ex.put("reason", reason);
        ex.put("distanceKm", e.c().distanceKm());
        ex.put("transitDays", transitDays);
        ex.put("onTime", round(onTime));
        ex.put("softScore", round(e.soft()));
        ex.put("unitCost", round(e.unitCost()));
        ex.put("preference", round(e.preference()));
        ex.put("penalty", round(ctx.penalties().forPackage(e.node(), skus)));
        ex.put("cost", econ.total().amount());
        if (econ.landedDetail() != null) ex.put("landedCost", Map.of("origin", e.node().customsOrigin(), "tariffRateId", econ.landedDetail().tariffRateId() == null ? "" : econ.landedDetail().tariffRateId(), "total", econ.landed().amount()));
        return new PackageBuild(pkg, econ, allocs, ex);
    }

    private SourcingPlan assemble(BuildContext ctx, String policy, List<PackageBuild> builds, List<String> unsourced, Map<String, Object> extra) {
        String ccy = ctx.req().currency();
        Money split = EconomicsCalculator.splitPenalty(ctx.splitPenaltyUsd(), builds.size(), ccy);
        Money total = split;
        List<PlanPackage> packages = new ArrayList<>(builds.size());
        List<PackageEconomics> econs = new ArrayList<>(builds.size());
        List<LineAlloc> allocated = new ArrayList<>();
        List<Map<String, Object>> nodeExplains = new ArrayList<>();
        for (PackageBuild b : builds) {
            packages.add(b.pkg());
            econs.add(b.econ());
            allocated.addAll(b.allocs());
            nodeExplains.add(b.explain());
            total = total.add(b.econ().total());
        }
        Pnl pnl = economics.pnl(allocated, ctx.req().shippingRevenue(), econs, split);
        boolean meets = pnl.meetsMarginFloor(ctx.marginFloor());
        Map<String, Object> explain = new LinkedHashMap<>();
        explain.put("policy", policy);
        explain.put("nodes", nodeExplains);
        explain.put("packages", packages.size());
        explain.put("splitPenalty", split.amount());
        explain.put("marginRatio", pnl.marginRatio());
        explain.put("marginFloor", ctx.marginFloor());
        explain.put("meetsMarginFloor", meets);
        if (!unsourced.isEmpty()) explain.put("unsourcedLineIds", List.copyOf(unsourced));
        if (SimulationContext.active()) explain.put("simulation", SimulationContext.current());
        explain.putAll(extra);
        return new SourcingPlan(Ids.prefixed("plan"), policy, packages, total, pnl, 0.0, meets, unsourced, explain);
    }

    private static double clamp(double v) { return Math.max(0.0, Math.min(1.0, v)); }
    private static double round(double v) { return Math.round(v * 10_000) / 10_000d; }
    private static String fmt(double v) { return String.format(java.util.Locale.ROOT, "%.3f", v); }
}
