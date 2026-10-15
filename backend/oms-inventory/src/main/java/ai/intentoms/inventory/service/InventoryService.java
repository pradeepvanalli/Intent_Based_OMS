package ai.intentoms.inventory.service;

import ai.intentoms.inventory.model.Atp;
import ai.intentoms.inventory.model.InventoryPosition;
import ai.intentoms.inventory.model.ReconciliationReport;
import ai.intentoms.platform.ledger.Actor;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Available-to-promise, feeds and reconciliation (FR-INV-01, FR-INV-03, FR-INV-04).
 * All methods are scoped to {@code TenantContext.require()}.
 */
public interface InventoryService {
    /** ATP per node for a sku, ordered by node id. Nodes with no position rows are absent. */
    List<Atp> atp(String sku);

    /** ATP at one node; zeros when the node holds no position for the sku. */
    Atp atp(String sku, String nodeId);

    /**
     * Channel-facing availability: sku -> sum over nodes of {@code max(0, available)} with the tenant's
     * {@code inventory.channel_buffer} fraction withheld and rounded down (FR-INV-03). Served from a short-TTL cache
     * that writes invalidate, so it is safe on the promise path (NFR-01). Unknown skus map to 0.
     */
    Map<String, Integer> networkAtp(Collection<String> skus);

    /**
     * Bulk feed from WMS/POS (FR-INV-04): upserts positions in one batch, logs the sync and writes one
     * {@code inventory.synced} event per node. Stale positions (older {@code asOf}) are ignored.
     */
    void upsert(List<InventoryPosition> positions, String source);

    /** Manual on-hand correction with a reason; writes {@code inventory.adjusted}. */
    void adjust(String sku, String nodeId, int delta, String reason, Actor actor);

    /**
     * Compares the snapshot's on-hand entries with stored on-hand per sku/node, logs the run and writes
     * {@code inventory.drift_detected} per node whose drift exceeds {@code inventory.drift_tolerance}. Does not correct
     * stock: operators act through {@link #upsert} or {@link #adjust}.
     */
    ReconciliationReport reconcile(List<InventoryPosition> snapshot);
}
