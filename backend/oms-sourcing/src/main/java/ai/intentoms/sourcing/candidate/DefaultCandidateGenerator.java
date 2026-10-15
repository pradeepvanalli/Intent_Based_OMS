package ai.intentoms.sourcing.candidate;

import ai.intentoms.inventory.model.Atp;
import ai.intentoms.inventory.service.InventoryService;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.time.TimeSource;
import ai.intentoms.sourcing.CandidateGenerator;
import ai.intentoms.sourcing.NodeRegistry;
import ai.intentoms.sourcing.config.SourcingSettings;
import ai.intentoms.sourcing.model.Candidate;
import ai.intentoms.sourcing.model.Node;
import ai.intentoms.sourcing.model.SimulationContext;
import ai.intentoms.sourcing.model.SimulationOverrides;
import ai.intentoms.sourcing.model.SourcingRequest;
import ai.intentoms.sourcing.rates.GeoPoint;
import ai.intentoms.sourcing.rates.RateCard;
import ai.intentoms.sourcing.rates.RateCardRepository;
import ai.intentoms.sourcing.rates.Transit;
import ai.intentoms.sourcing.rates.ZipGeo;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Deterministic candidate generation (FR-SRC-01). Filters, in order: active and not closed/excluded; node type and
 * required capabilities (hazmat lines → {@code hazmat}; pickup → {@code bopis}; same-day → {@code same_day}; anything
 * shipped → {@code ship} or {@code sfs}); some coverage from ATP (one {@code atp(sku)} call per sku); remaining daily
 * capacity; distance limit. Transit = rate-card zone days + cutoff roll-over + unknown-destination penalty + simulated
 * carrier outage. Sorted by complete coverage desc, distance asc, node id asc; truncated to the limit.
 */
@Service
public class DefaultCandidateGenerator implements CandidateGenerator {
    private final NodeRegistry nodes;
    private final InventoryService inventory;
    private final NodeLoadRepository load;
    private final RateCardRepository rates;
    private final SourcingSettings settings;
    private final TimeSource time;
    private final Metrics metrics;

    public DefaultCandidateGenerator(NodeRegistry nodes, InventoryService inventory, NodeLoadRepository load, RateCardRepository rates, SourcingSettings settings, TimeSource time, Metrics metrics) {
        this.nodes = nodes;
        this.inventory = inventory;
        this.load = load;
        this.rates = rates;
        this.settings = settings;
        this.time = time;
        this.metrics = metrics;
    }

    @Override
    public List<Candidate> generate(SourcingRequest req, int limit) {
        int n = limit > 0 ? limit : settings.candidateLimit();
        return metrics.time("oms.sourcing.candidates", () -> doGenerate(req, n));
    }

    private List<Candidate> doGenerate(SourcingRequest req, int limit) {
        if (req.lines().isEmpty()) return List.of();
        Instant now = time.now();
        SimulationOverrides sim = SimulationContext.current();
        RateCard card = rates.current();
        GeoPoint dest = ZipGeo.locate(req.destination().zip(), req.destination().state(), req.destination().country());
        Map<String, Integer> wanted = req.unitsBySku();
        Set<String> required = requiredCapabilities(req);
        SourcingRequest.Constraints c = req.constraints();

        // Availability per sku per node: one ATP call per sku (batch-friendly, FR-INV-01).
        Map<String, Map<String, Integer>> availBySku = new HashMap<>();
        for (String sku : wanted.keySet()) {
            Map<String, Integer> perNode = new HashMap<>();
            for (Atp a : inventory.atp(sku)) if (a.available() > 0) perNode.put(a.nodeId(), a.available());
            availBySku.put(sku, perNode);
        }
        Map<String, Integer> released = load.unitsReleasedSince(now.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant());

        List<Candidate> out = new ArrayList<>();
        for (Node node : nodes.active()) {
            if (sim.closed(node.id()) || c.excludedNodes().contains(node.id())) continue;
            if (!c.nodeTypes().isEmpty() && !c.nodeTypes().contains(node.type())) continue;
            if (!node.capabilities().containsAll(required)) continue;
            if (!req.isPickup() && !node.canShip()) continue;

            int remainingCapacity = node.dailyCapacity() <= 0 ? Integer.MAX_VALUE : node.dailyCapacity() - released.getOrDefault(node.id(), 0);
            if (remainingCapacity <= 0) continue;

            Map<String, Integer> coverage = new TreeMap<>();
            int budget = remainingCapacity;
            boolean complete = true;
            for (var e : wanted.entrySet()) {
                int avail = availBySku.getOrDefault(e.getKey(), Map.of()).getOrDefault(node.id(), 0);
                int q = Math.min(Math.min(avail, e.getValue()), budget);
                if (q > 0) { coverage.put(e.getKey(), q); budget -= q; }
                if (q < e.getValue()) complete = false;
            }
            if (coverage.isEmpty()) continue;

            double distance = dest.distanceKm(node.lat(), node.lon());
            if (c.maxDistanceKm() != null && distance > c.maxDistanceKm()) continue;
            if (req.isPickup() && c.maxDistanceKm() == null && distance > settings.pickupRadiusKm() * 4) continue;

            int transit = req.isPickup() ? 0 : card.transitDays(req.shipMethod(), distance) + dest.transitPenaltyDays() + sim.extraTransitDays();
            transit += Transit.cutoffDelayDays(node, now);
            out.add(new Candidate(node, coverage, complete, Math.round(distance * 10) / 10.0, transit));
        }
        out.sort(Comparator.comparing((Candidate x) -> !x.complete()).thenComparingDouble(Candidate::distanceKm).thenComparing(Candidate::nodeId));
        metrics.count("oms.sourcing.candidates.generated", out.size());
        return out.size() > limit ? List.copyOf(out.subList(0, limit)) : out;
    }

    /** Capabilities every candidate must have for this request (FR-SRC-01). */
    public static Set<String> requiredCapabilities(SourcingRequest req) {
        Set<String> required = new LinkedHashSet<>(req.constraints().capabilities());
        if (req.hasHazmat()) required.add(Node.CAP_HAZMAT);
        if (req.isPickup()) required.add(Node.CAP_BOPIS);
        if (SourcingRequest.METHOD_SAME_DAY.equalsIgnoreCase(req.shipMethod())) required.add(Node.CAP_SAME_DAY);
        return required;
    }

    /** Diagnostic view of why nodes were excluded; used by the options endpoint's explain. */
    public Map<String, Object> describe(SourcingRequest req) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requiredCapabilities", List.copyOf(requiredCapabilities(req)));
        m.put("destination", ZipGeo.locate(req.destination().zip(), req.destination().state(), req.destination().country()));
        m.put("excludedNodes", List.copyOf(req.constraints().excludedNodes()));
        m.put("simulation", SimulationContext.current());
        return m;
    }
}
