package ai.intentoms.inventory.service;

import ai.intentoms.inventory.config.InventoryConfigKeys;
import ai.intentoms.inventory.learning.LearnedInventoryParameters;
import ai.intentoms.inventory.model.Atp;
import ai.intentoms.inventory.model.InventoryPosition;
import ai.intentoms.inventory.model.ReconciliationReport;
import ai.intentoms.inventory.store.InventoryPositionStore;
import ai.intentoms.inventory.store.InventoryPositionStore.AtpRow;
import ai.intentoms.inventory.store.SyncLogStore;
import ai.intentoms.platform.config.TenantConfigStore;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.ledger.Actor;
import ai.intentoms.platform.ledger.EventStore;
import ai.intentoms.platform.ledger.NewEvent;
import ai.intentoms.platform.observability.Metrics;
import ai.intentoms.platform.security.AuthorizationService;
import ai.intentoms.platform.tenant.TenantContext;
import ai.intentoms.platform.time.TimeSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Default {@link InventoryService} over PostgreSQL (FR-INV-01, FR-INV-03, FR-INV-04). */
@Service
public class DefaultInventoryService implements InventoryService {
    private final InventoryPositionStore positions;
    private final SyncLogStore syncLog;
    private final EventStore events;
    private final TenantConfigStore config;
    private final NetworkAtpCache cache;
    private final Metrics metrics;
    private final TimeSource time;
    private final AuthorizationService authz;
    private final LearnedInventoryParameters learned;

    public DefaultInventoryService(InventoryPositionStore positions, SyncLogStore syncLog, EventStore events, TenantConfigStore config,
                                   NetworkAtpCache cache, Metrics metrics, TimeSource time, AuthorizationService authz, LearnedInventoryParameters learned) {
        this.learned = learned;
        this.positions = positions;
        this.syncLog = syncLog;
        this.events = events;
        this.config = config;
        this.cache = cache;
        this.metrics = metrics;
        this.time = time;
        this.authz = authz;
    }

    @Override
    public List<Atp> atp(String sku) {
        String tenant = TenantContext.require();
        return metrics.time("oms.inventory.atp", () -> {
            boolean inbound = includeInbound();
            List<AtpRow> rows = learned.apply(tenant, positions.atpRows(tenant, sku, null), inbound);
            List<Atp> out = new ArrayList<>(rows.size());
            for (AtpRow r : rows) out.add(toAtp(r, inbound));
            return out;
        }, "scope", "sku");
    }

    @Override
    public Atp atp(String sku, String nodeId) {
        String tenant = TenantContext.require();
        return metrics.time("oms.inventory.atp", () -> {
            boolean inbound = includeInbound();
            List<AtpRow> rows = learned.apply(tenant, positions.atpRows(tenant, sku, nodeId), inbound);
            return rows.isEmpty() ? new Atp(sku, nodeId, 0, 0, 0, 0, 0, 0, 1.0) : toAtp(rows.get(0), inbound);
        }, "scope", "node");
    }

    @Override
    public Map<String, Integer> networkAtp(Collection<String> skus) {
        String tenant = TenantContext.require();
        return metrics.time("oms.inventory.atp", () -> {
            boolean inbound = includeInbound();
            double buffer = config.getDouble(InventoryConfigKeys.CHANNEL_BUFFER, 0.0);
            Map<String, Integer> out = new LinkedHashMap<>();
            List<String> misses = new ArrayList<>();
            for (String sku : new LinkedHashSet<>(skus)) {
                NetworkAtpCache.Entry e = cache.get(tenant, sku);
                if (e == null) { misses.add(sku); out.put(sku, 0); }
                else out.put(sku, AtpMath.applyBuffer(e.total(inbound), buffer));
            }
            if (!misses.isEmpty()) {
                Map<String, NetworkAtpCache.Entry> fetched = loadNetwork(tenant, misses);
                for (String sku : misses) {
                    NetworkAtpCache.Entry e = fetched.getOrDefault(sku, new NetworkAtpCache.Entry(0, 0));
                    cache.put(tenant, sku, e);
                    out.put(sku, AtpMath.applyBuffer(e.total(inbound), buffer));
                }
            }
            return out;
        }, "scope", "network");
    }

    /** One grouped query for all missing skus; per-node clamping happens here so negative nodes never hide positive ones. */
    private Map<String, NetworkAtpCache.Entry> loadNetwork(String tenant, List<String> skus) {
        Map<String, int[]> totals = new HashMap<>();
        List<AtpRow> raw = positions.atpRows(tenant, skus);
        List<AtpRow> withoutInbound = learned.apply(tenant, raw, false);
        List<AtpRow> withInbound = learned.apply(tenant, raw, true);
        for (int i = 0; i < raw.size(); i++) {
            int[] t = totals.computeIfAbsent(raw.get(i).sku(), k -> new int[2]);
            t[0] += Math.max(0, available(withoutInbound.get(i), false));
            t[1] += Math.max(0, available(withInbound.get(i), true));
        }
        Map<String, NetworkAtpCache.Entry> out = new HashMap<>();
        totals.forEach((sku, t) -> out.put(sku, new NetworkAtpCache.Entry(t[0], t[1])));
        return out;
    }

    @Override
    @Transactional
    public void upsert(List<InventoryPosition> rawPositions, String source) {
        if (source == null || source.isBlank()) throw new ValidationException("source is required");
        String tenant = TenantContext.require();
        Instant now = time.now();
        List<InventoryPosition> normalised = new ArrayList<>(rawPositions.size());
        Map<String, Set<String>> skusByNode = new TreeMap<>();
        for (InventoryPosition p : rawPositions) {
            InventoryPosition n = p.normalised(now);
            normalised.add(n);
            skusByNode.computeIfAbsent(n.nodeId(), k -> new LinkedHashSet<>()).add(n.sku());
        }
        int written = positions.upsertAll(tenant, normalised, now);
        syncLog.append(tenant, source, normalised.size(), null, now);
        Actor actor = actor();
        Map<String, Integer> countByNode = new HashMap<>();
        for (InventoryPosition p : normalised) countByNode.merge(p.nodeId(), 1, Integer::sum);
        for (var e : skusByNode.entrySet()) {
            events.append(InventoryEvents.NODE, e.getKey(), new NewEvent(InventoryEvents.INVENTORY_SYNCED,
                Map.of("nodeId", e.getKey(), "source", source, "positions", countByNode.get(e.getKey()), "skus", e.getValue().size()), actor));
            cache.invalidateAll(tenant, e.getValue());
        }
        metrics.count("oms.inventory.feed.positions", normalised.size(), "source", source);
        metrics.count("oms.inventory.feed.written", written, "source", source);
    }

    @Override
    @Transactional
    public void adjust(String sku, String nodeId, int delta, String reason, Actor actor) {
        if (reason == null || reason.isBlank()) throw new ValidationException("reason is required");
        if (delta == 0) throw new ValidationException("delta must be non-zero");
        String tenant = TenantContext.require();
        Instant now = time.now();
        positions.lockOnHand(tenant, sku, nodeId);
        int after = positions.addOnHand(tenant, sku, nodeId, delta, now);
        events.append(InventoryEvents.NODE, nodeId, new NewEvent(InventoryEvents.INVENTORY_ADJUSTED,
            Map.of("nodeId", nodeId, "sku", sku, "delta", delta, "onHandAfter", after, "reason", reason), actor == null ? actor() : actor));
        cache.invalidate(tenant, sku);
        metrics.count("oms.inventory.adjusted");
    }

    @Override
    @Transactional
    public ReconciliationReport reconcile(List<InventoryPosition> snapshot) {
        String tenant = TenantContext.require();
        Instant now = time.now();
        int tolerance = Math.max(0, config.getInt(InventoryConfigKeys.DRIFT_TOLERANCE, 0));
        List<InventoryPosition> onHand = new ArrayList<>();
        Set<String> skus = new LinkedHashSet<>();
        for (InventoryPosition p : snapshot) {
            InventoryPosition n = p.normalised(now);
            if (n.isSupply() && InventoryPosition.ON_HAND.equals(n.supplyType())) { onHand.add(n); skus.add(n.sku()); }
        }
        Map<String, Integer> stored = positions.onHandBySkuNode(tenant, skus);
        Map<String, List<ReconciliationReport.Drift>> byNode = new TreeMap<>();
        List<ReconciliationReport.Drift> drifted = new ArrayList<>();
        for (InventoryPosition p : onHand) {
            int actual = stored.getOrDefault(p.sku() + "|" + p.nodeId(), 0);
            int delta = actual - p.qty();
            if (Math.abs(delta) > tolerance) {
                var d = new ReconciliationReport.Drift(p.sku(), p.nodeId(), p.qty(), actual, delta);
                drifted.add(d);
                byNode.computeIfAbsent(p.nodeId(), k -> new ArrayList<>()).add(d);
            }
        }
        String logId = syncLog.append(tenant, "reconcile", onHand.size(), Map.of("tolerance", tolerance, "checked", onHand.size(), "drifted", drifted), now);
        Actor actor = actor();
        for (var e : byNode.entrySet()) {
            events.append(InventoryEvents.NODE, e.getKey(), new NewEvent(InventoryEvents.INVENTORY_DRIFT_DETECTED,
                Map.of("nodeId", e.getKey(), "tolerance", tolerance, "drifted", e.getValue(), "syncLogId", logId), actor));
        }
        metrics.count("oms.inventory.reconcile.checked", onHand.size());
        metrics.count("oms.inventory.reconcile.drifted", drifted.size());
        return new ReconciliationReport(onHand.size(), List.copyOf(drifted));
    }

    private boolean includeInbound() { return config.getBoolean(InventoryConfigKeys.INCLUDE_INBOUND, false); }

    private Actor actor() { return Actor.parse(authz.actorRef()); }

    static int available(AtpRow r, boolean includeInbound) {
        return AtpMath.available(r.onHand(), r.inbound(), includeInbound, r.reserved() + r.externalReserved(), r.allocated(), r.safety());
    }

    static Atp toAtp(AtpRow r, boolean includeInbound) {
        return new Atp(r.sku(), r.nodeId(), r.onHand(), r.inbound(), r.reserved() + r.externalReserved(), r.allocated(), r.safety(),
            available(r, includeInbound), r.confidence());
    }
}
