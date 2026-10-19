package ai.intentoms.sourcing.promise;

import ai.intentoms.finance.model.Money;
import ai.intentoms.inventory.model.Atp;
import ai.intentoms.inventory.service.InventoryService;
import ai.intentoms.learning.governance.ParameterResolver;
import ai.intentoms.learning.learner.LaneDeliveryLearner;
import ai.intentoms.learning.learner.NodeAssignmentRepository;
import ai.intentoms.learning.learner.PromiseBufferLearner;
import ai.intentoms.learning.model.FactKey;
import ai.intentoms.learning.model.LearnedFact;
import ai.intentoms.learning.model.SubjectType;
import ai.intentoms.learning.model.TypedValue;
import ai.intentoms.learning.store.LearnedFactCache;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.platform.time.TimeSource;
import ai.intentoms.sourcing.NodeRegistry;
import ai.intentoms.sourcing.PromiseService;
import ai.intentoms.sourcing.config.SourcingProperties;
import ai.intentoms.sourcing.config.SourcingSettings;
import ai.intentoms.sourcing.learning.LaneKnowledge;
import ai.intentoms.sourcing.model.Node;
import ai.intentoms.sourcing.model.Promise;
import ai.intentoms.sourcing.model.PromiseRequest;
import ai.intentoms.sourcing.model.SimulationContext;
import ai.intentoms.sourcing.model.SimulationOverrides;
import ai.intentoms.sourcing.model.SourcingRequest;
import ai.intentoms.sourcing.rates.GeoPoint;
import ai.intentoms.sourcing.rates.RateCard;
import ai.intentoms.sourcing.rates.RateCardRepository;
import ai.intentoms.sourcing.rates.Transit;
import ai.intentoms.sourcing.rates.ZipGeo;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Promise service (FR-SRC-05, FR-SRC-06; NFR-02 p95 &lt; 300 ms; NFR-01 20k reads/s). Deterministic: ATP per sku (the
 * inventory module's cached reads), the rate card's zone transit, node cutoff and reliability — no decision call. The
 * node is the one that covers every requested unit with the earliest ETA (then highest on-time, then lowest cost); when
 * no single node covers everything the promise is the latest of the per-sku best nodes; with no stock anywhere the date
 * is today + {@code sourcing.backorder_lead_days} with on-time 0.5 and no node. Cached in Caffeine (TTL 30 s) and in the
 * optional {@link PromiseCacheL2}. Simulation overrides (closed nodes, carrier outage) apply when set.
 *
 * <p>Learned inputs (each served only when its parameter family is promoted for live use, else the static value):
 * FS-03 transit days and CX-01 lane calibration, read through {@link LaneKnowledge} exactly as sourcing plans read
 * them (so a promise and the order's later promise date agree); CX-02 a per-channel buffer added to the date
 * ({@code promise.buffer_days}, static value from the same tenant config key).
 */
@Service
public class DefaultPromiseService implements PromiseService {
    private static final double DEFAULT_UNIT_WEIGHT_KG = 0.5;

    private final NodeRegistry nodes;
    private final InventoryService inventory;
    private final RateCardRepository rates;
    private final SourcingSettings settings;
    private final PromiseCacheL2 l2;
    private final TimeSource time;
    private final Metrics metrics;
    private final Duration ttl;
    private final Cache<String, Promise> cache;
    private final LearnedFactCache facts;
    private final ParameterResolver parameters;
    private final TenantConfigStore config;
    private final LaneKnowledge lanes;

    public DefaultPromiseService(NodeRegistry nodes, InventoryService inventory, RateCardRepository rates, SourcingSettings settings, PromiseCacheL2 l2, TimeSource time, Metrics metrics, SourcingProperties props,
                                 LearnedFactCache facts, ParameterResolver parameters, TenantConfigStore config, LaneKnowledge lanes) {
        this.facts = facts;
        this.parameters = parameters;
        this.config = config;
        this.lanes = lanes;
        this.nodes = nodes;
        this.inventory = inventory;
        this.rates = rates;
        this.settings = settings;
        this.l2 = l2;
        this.time = time;
        this.metrics = metrics;
        this.ttl = props.promise().cacheTtl();
        this.cache = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(props.promise().cacheMaxSize()).recordStats().build();
    }

    @Override
    public Promise promise(PromiseRequest request) {
        long start = System.nanoTime();
        validate(request);
        String tenant = TenantContext.require();
        String key = PromiseKey.of(tenant, request);
        Promise hit = SimulationContext.active() ? null : cache.getIfPresent(key);
        if (hit != null) return done(hit.asCached(elapsed(start)), "l1");
        if (!SimulationContext.active()) {
            Optional<Promise> l2hit = l2.get(key);
            if (l2hit.isPresent()) { cache.put(key, l2hit.get()); return done(l2hit.get().asCached(elapsed(start)), "l2"); }
        }
        Promise computed = compute(request);
        if (!SimulationContext.active()) { cache.put(key, computed); l2.put(key, computed, ttl); }
        return done(computed.withLatency(elapsed(start)), "miss");
    }

    /** Caffeine statistics for the health endpoint. */
    public Map<String, Object> cacheStats() {
        var s = cache.stats();
        return Map.of("hitCount", s.hitCount(), "missCount", s.missCount(), "hitRate", s.hitRate(), "size", cache.estimatedSize(), "ttlSeconds", ttl.toSeconds(), "l2", l2.name());
    }

    public void invalidateAll() { cache.invalidateAll(); }

    // ---- computation --------------------------------------------------------------------------------------------------

    private record NodeOption(Node node, double distanceKm, int transitDays, double onTime, Money cost, LocalDate eta) {}

    Promise compute(PromiseRequest r) {
        Instant now = time.now();
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        RateCard card = rates.current();
        SimulationOverrides sim = SimulationContext.current();
        GeoPoint dest = ZipGeo.locate(r.destinationZip(), r.destinationState(), "US");
        boolean pickup = SourcingRequest.METHOD_PICKUP.equalsIgnoreCase(r.shipMethod());
        String zip3 = LaneDeliveryLearner.zip3(r.destinationZip());
        Map<String, Integer> wanted = new HashMap<>();
        for (PromiseRequest.PromiseLine l : r.lines()) wanted.merge(l.sku(), l.qty(), Integer::sum);
        int totalUnits = wanted.values().stream().mapToInt(Integer::intValue).sum();
        double weight = totalUnits * DEFAULT_UNIT_WEIGHT_KG;

        Map<String, Map<String, Integer>> availBySku = new HashMap<>();
        for (String sku : wanted.keySet()) {
            Map<String, Integer> perNode = new HashMap<>();
            for (Atp a : inventory.atp(sku)) if (a.available() > 0) perNode.put(a.nodeId(), a.available());
            availBySku.put(sku, perNode);
        }

        List<Node> active = nodes.active();
        List<NodeOption> full = new ArrayList<>();
        Map<String, NodeOption> bestPerSku = new HashMap<>();
        for (Node node : active) {
            if (sim.closed(node.id())) continue;
            if (pickup ? !node.has(Node.CAP_BOPIS) : !node.canShip()) continue;
            if (SourcingRequest.METHOD_SAME_DAY.equalsIgnoreCase(r.shipMethod()) && !node.has(Node.CAP_SAME_DAY)) continue;
            boolean coversAll = true;
            List<String> covered = new ArrayList<>();
            for (var e : wanted.entrySet()) {
                int avail = availBySku.get(e.getKey()).getOrDefault(node.id(), 0);
                if (avail >= e.getValue()) covered.add(e.getKey()); else coversAll = false;
            }
            if (covered.isEmpty()) continue;
            NodeOption opt = option(card, node, dest, zip3, r.shipMethod(), weight, now, today, sim, pickup);
            if (coversAll) full.add(opt);
            for (String sku : covered) bestPerSku.merge(sku, opt, DefaultPromiseService::better);
        }

        NodeOption chosen;
        if (!full.isEmpty()) chosen = full.stream().reduce(DefaultPromiseService::better).orElseThrow();
        else if (bestPerSku.size() == wanted.size()) chosen = bestPerSku.values().stream().reduce((a, b) -> a.eta().isAfter(b.eta()) ? a : b).orElseThrow();
        else chosen = null;

        if (chosen == null) {
            LocalDate date = Transit.eta(today, settings.backorderLeadDays(), settings.businessDaysOnly());
            return new Promise(date, 0.5, null, r.shipMethod(), false, 0, List.of());
        }
        int buffer = pickup ? 0 : bufferDays(r.channel());
        List<Promise.PromiseOption> alternatives = new ArrayList<>();
        if (!pickup) {
            for (String service : card.services()) {
                if (service.equalsIgnoreCase(r.shipMethod())) continue;
                if (SourcingRequest.METHOD_SAME_DAY.equals(service) && (!chosen.node().has(Node.CAP_SAME_DAY) || chosen.distanceKm() > 40)) continue;
                NodeOption alt = option(card, chosen.node(), dest, zip3, service, weight, now, today, sim, false);
                alternatives.add(new Promise.PromiseOption(service, buffered(alt.eta(), buffer), alt.onTime(), chosen.node().id(), alt.cost().subtract(chosen.cost())));
            }
            alternatives.sort((a, b) -> a.date().compareTo(b.date()));
        }
        return new Promise(buffered(chosen.eta(), buffer), chosen.onTime(), chosen.node().id(), r.shipMethod(), false, 0, alternatives);
    }

    /** CX-02: whole days of padding for this channel's promise. */
    int bufferDays(String channel) {
        double staticBuffer = config.getDouble(PromiseBufferLearner.PARAMETER, 0.0);
        Double learned = number(SubjectType.SEGMENT, PromiseBufferLearner.segment(channel), FactKey.PROMISE_BUFFER_DAYS, 1)
            .or(() -> number(SubjectType.SEGMENT, PromiseBufferLearner.segment(null), FactKey.PROMISE_BUFFER_DAYS, 1)).orElse(null);
        Double served = parameters.explain(PromiseBufferLearner.PARAMETER, staticBuffer, learned).value();
        return served == null ? 0 : (int) Math.max(0, Math.round(served));
    }

    private LocalDate buffered(LocalDate eta, int buffer) {
        return buffer <= 0 || eta == null ? eta : Transit.eta(eta, buffer, settings.businessDaysOnly());
    }

    /** FS-03: the carrier a service ships with from this node (tenant map first, then what shipments show). */
    String carrierFor(String service, String nodeId) { return lanes.carrierFor(service, nodeId); }

    private Optional<Double> number(SubjectType st, String subjectId, FactKey key, int minSamples) {
        try {
            Optional<LearnedFact> f = facts.active(st, subjectId, key);
            return f.filter(x -> x.sampleSize() >= minSamples && x.value() instanceof TypedValue.NumberValue).map(x -> ((TypedValue.NumberValue) x.value()).value());
        } catch (RuntimeException e) {
            return Optional.empty(); // learning unavailable: the static promise still works
        }
    }

    private NodeOption option(RateCard card, Node node, GeoPoint dest, String zip3, String method, double weightKg, Instant now, LocalDate today, SimulationOverrides sim, boolean pickup) {
        double distance = dest.distanceKm(node.lat(), node.lon());
        RateCard.Quote q = card.quote(pickup ? RateCard.PICKUP : method, distance, weightKg);
        int carrierTransit = pickup ? 0 : q.transitDays() + dest.transitPenaltyDays();
        if (!pickup) carrierTransit = lanes.transit(method, node.id(), zip3, carrierTransit).days();
        int transit = carrierTransit + (pickup ? 0 : sim.extraTransitDays()) + Transit.cutoffDelayDays(node, now);
        double onTime = clamp(card.onTimeProbability(transit, node.reliability()) * (1 - node.penalty()) + sim.onTimeDelta());
        if (!pickup) onTime = clamp(onTime + lanes.calibration(method, node.id(), zip3));
        return new NodeOption(node, distance, transit, round(onTime), q.cost(), Transit.eta(today, transit, settings.businessDaysOnly()));
    }

    /** Earlier ETA wins, then higher on-time, then lower cost, then node id for determinism. */
    private static NodeOption better(NodeOption a, NodeOption b) {
        int c = a.eta().compareTo(b.eta());
        if (c != 0) return c < 0 ? a : b;
        if (a.onTime() != b.onTime()) return a.onTime() > b.onTime() ? a : b;
        c = a.cost().compareTo(b.cost());
        if (c != 0) return c < 0 ? a : b;
        return a.node().id().compareTo(b.node().id()) <= 0 ? a : b;
    }

    private Promise done(Promise p, String cacheTag) {
        metrics.record("oms.promise.latency", Duration.ofMillis(Math.max(0, p.latencyMs())), "cache", cacheTag);
        metrics.count("oms.promise.requests", "cache", cacheTag);
        return p;
    }

    private static void validate(PromiseRequest r) {
        if (r == null || r.lines().isEmpty()) throw new ValidationException("promise.lines must list at least one sku");
        for (PromiseRequest.PromiseLine l : r.lines()) {
            if (l.sku() == null || l.sku().isBlank()) throw new ValidationException("promise line sku is required");
            if (l.qty() <= 0) throw new ValidationException("promise line qty must be > 0", Map.of("sku", l.sku()));
        }
        if ((r.destinationZip() == null || r.destinationZip().isBlank()) && (r.destinationState() == null || r.destinationState().isBlank()))
            throw new ValidationException("destinationZip or destinationState is required");
        if (r.shipMethod() != null && r.shipMethod().toLowerCase(Locale.ROOT).length() > 32) throw new ValidationException("shipMethod too long");
    }

    private static long elapsed(long startNanos) { return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000); }
    private static double clamp(double v) { return Math.max(0.0, Math.min(1.0, v)); }
    private static double round(double v) { return Math.round(v * 10_000) / 10_000d; }
}
