package ai.intentoms.sourcing.node;

import ai.intentoms.platform.error.NotFoundException;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.platform.time.TimeSource;
import ai.intentoms.sourcing.NodeRegistry;
import ai.intentoms.sourcing.model.Node;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Node registry over {@code node} / {@code node_penalty} with a 5 s read cache per tenant (the promise path reads every
 * active node per request; writes invalidate eagerly). Penalties decay with a 7-day half-life (FR-SRC-07); the
 * node-level effective penalty is attached to every {@link Node} returned.
 */
@Service
public class DefaultNodeRegistry implements NodeRegistry {
    public static final String EVT_UPSERTED = "node.upserted";
    public static final String EVT_PENALISED = "node.penalised";
    public static final String EVT_HEALTH_UPDATED = "node.health_updated";
    private static final Duration TTL = Duration.ofSeconds(5);

    private record Snapshot(List<Node> all, Map<String, Node> byId, Map<String, Map<String, NodeRepository.PenaltyRow>> penalties) {}

    private final NodeRepository repo;
    private final EventStore events;
    private final TimeSource time;
    private final AuthorizationService authz;
    private final Metrics metrics;
    private final Cache<String, Snapshot> cache = Caffeine.newBuilder().expireAfterWrite(TTL).maximumSize(1_000).build();

    public DefaultNodeRegistry(NodeRepository repo, EventStore events, TimeSource time, AuthorizationService authz, Metrics metrics) {
        this.repo = repo;
        this.events = events;
        this.time = time;
        this.authz = authz;
        this.metrics = metrics;
    }

    @Override
    public Node get(String id) { return find(id).orElseThrow(() -> new NotFoundException("Node " + id + " not found")); }

    @Override
    public Optional<Node> find(String id) {
        Snapshot s = snapshot();
        Node n = s.byId().get(id);
        return n == null ? Optional.empty() : Optional.of(withPenalty(n, s));
    }

    @Override
    public List<Node> active() {
        Snapshot s = snapshot();
        List<Node> out = new ArrayList<>(s.all().size());
        for (Node n : s.all()) if (n.active()) out.add(withPenalty(n, s));
        return out;
    }

    @Override
    public List<Node> all() {
        Snapshot s = snapshot();
        List<Node> out = new ArrayList<>(s.all().size());
        for (Node n : s.all()) out.add(withPenalty(n, s));
        return out;
    }

    @Override
    @Transactional
    public Node upsert(Node n) {
        validate(n);
        repo.upsert(n);
        events.append("node", n.id(), new NewEvent(EVT_UPSERTED, Map.of("nodeId", n.id(), "type", n.type(), "active", n.active(), "capabilities", List.copyOf(n.capabilities()),
            "dailyCapacity", n.dailyCapacity(), "country", n.country(), "state", n.state() == null ? "" : n.state()), actor()));
        invalidate();
        metrics.count("oms.sourcing.node.upserted");
        return get(n.id());
    }

    @Override
    @Transactional
    public int clearPenalties(String nodeId, String reason) {
        Node node = get(nodeId);
        int n = repo.clearPenalties(nodeId);
        invalidate();
        events.append("node", node.id(), new NewEvent("node.penalties_cleared", Map.of("nodeId", node.id(), "cleared", n, "reason", reason == null ? "" : reason), Actor.parse(authz.actorRef())));
        return n;
    }

    @Override
    public void penalise(String nodeId, String sku, double delta, String reason) {
        if (delta <= 0) throw new ValidationException("penalty delta must be > 0", Map.of("nodeId", nodeId, "delta", delta));
        Node node = get(nodeId);
        Instant now = time.now();
        double before = rawEffective(nodeId, sku, now);
        double after = PenaltyDecay.clamp(before + delta);
        repo.writePenalty(nodeId, sku, after, reason, now);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodeId", node.id());
        payload.put("sku", sku == null ? "*" : sku);
        payload.put("delta", delta);
        payload.put("penaltyBefore", round(before));
        payload.put("penaltyAfter", round(after));
        payload.put("reason", reason == null ? "" : reason);
        events.append("node", nodeId, new NewEvent(EVT_PENALISED, payload, actor()));
        invalidate();
        metrics.count("oms.sourcing.node.penalised", "node", nodeId);
    }

    @Override
    public double penalty(String nodeId, String sku) {
        Snapshot s = snapshot();
        Instant now = time.now();
        Map<String, NodeRepository.PenaltyRow> rows = s.penalties().getOrDefault(nodeId, Map.of());
        double p = effective(rows.get(NodeRepository.NODE_LEVEL), now);
        if (sku != null) p += effective(rows.get(sku), now);
        return PenaltyDecay.clamp(p);
    }

    @Override
    @Transactional
    public Node updateHealth(String nodeId, Map<String, Object> signals) {
        if (signals == null || signals.isEmpty()) throw new ValidationException("health signals must not be empty", Map.of("nodeId", nodeId));
        Node node = get(nodeId).mergeHealth(signals);
        repo.upsert(node);
        events.append("node", nodeId, new NewEvent(EVT_HEALTH_UPDATED, Map.of("nodeId", nodeId, "signals", new LinkedHashMap<>(signals), "health", new LinkedHashMap<>(node.health())), actor()));
        invalidate();
        metrics.count("oms.sourcing.node.health_updated", "node", nodeId);
        return get(nodeId);
    }

    /** Raw rows for the API (decayed to now). */
    public List<Map<String, Object>> penaltyRows(String nodeId) {
        Instant now = time.now();
        List<Map<String, Object>> out = new ArrayList<>();
        for (NodeRepository.PenaltyRow r : repo.penalties(nodeId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nodeId", r.nodeId());
            m.put("sku", r.sku());
            m.put("penalty", round(r.penalty()));
            m.put("effective", round(PenaltyDecay.effective(r.penalty(), r.updatedAt(), now)));
            m.put("reason", r.reason());
            m.put("updatedAt", r.updatedAt());
            out.add(m);
        }
        return out;
    }

    public void invalidate() { TenantContext.current().ifPresent(cache::invalidate); }

    private Snapshot snapshot() {
        return cache.get(TenantContext.require(), t -> {
            List<Node> all = repo.all(false);
            Map<String, Node> byId = new HashMap<>();
            for (Node n : all) byId.put(n.id(), n);
            Map<String, Map<String, NodeRepository.PenaltyRow>> pen = new HashMap<>();
            for (NodeRepository.PenaltyRow r : repo.allPenalties()) pen.computeIfAbsent(r.nodeId(), k -> new HashMap<>()).put(r.sku() == null ? NodeRepository.NODE_LEVEL : r.sku(), r);
            return new Snapshot(all, byId, pen);
        });
    }

    private Node withPenalty(Node n, Snapshot s) {
        Map<String, NodeRepository.PenaltyRow> rows = s.penalties().get(n.id());
        if (rows == null) return n.withPenalty(0);
        return n.withPenalty(PenaltyDecay.clamp(effective(rows.get(NodeRepository.NODE_LEVEL), time.now())));
    }

    private double rawEffective(String nodeId, String sku, Instant now) {
        for (NodeRepository.PenaltyRow r : repo.penalties(nodeId)) {
            boolean match = sku == null ? r.sku() == null : sku.equals(r.sku());
            if (match) return PenaltyDecay.effective(r.penalty(), r.updatedAt(), now);
        }
        return 0.0;
    }

    private static double effective(NodeRepository.PenaltyRow r, Instant now) { return r == null ? 0.0 : PenaltyDecay.effective(r.penalty(), r.updatedAt(), now); }

    private Actor actor() { return Actor.parse(authz.actorRef()); }

    private static double round(double v) { return Math.round(v * 10_000) / 10_000d; }

    private static void validate(Node n) {
        if (n.id() == null || n.id().isBlank()) throw new ValidationException("node.id is required");
        if (n.type() == null || n.type().isBlank()) throw new ValidationException("node.type is required", Map.of("nodeId", n.id()));
        if (Math.abs(n.lat()) > 90 || Math.abs(n.lon()) > 180) throw new ValidationException("node lat/lon out of range", Map.of("nodeId", n.id()));
        if (n.dailyCapacity() < 0) throw new ValidationException("node.dailyCapacity must be >= 0", Map.of("nodeId", n.id()));
        try { java.time.ZoneId.of(n.timezone()); } catch (RuntimeException e) { throw new ValidationException("node.timezone is not a valid zone: " + n.timezone(), Map.of("nodeId", n.id())); }
    }
}
