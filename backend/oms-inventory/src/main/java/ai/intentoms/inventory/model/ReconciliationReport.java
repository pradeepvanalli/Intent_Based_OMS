package ai.intentoms.inventory.model;

import java.util.List;

/**
 * Result of comparing a WMS/POS on-hand snapshot against stored positions (FR-INV-04).
 *
 * @param checked number of on-hand snapshot entries compared
 * @param drifted entries whose |delta| exceeds the tenant's {@code inventory.drift_tolerance} (default 0)
 */
public record ReconciliationReport(int checked, List<Drift> drifted) {
    /**
     * @param expected on-hand quantity reported by the snapshot (the source of truth)
     * @param actual   on-hand quantity stored in the OMS (0 when the position is unknown)
     * @param delta    actual - expected
     */
    public record Drift(String sku, String nodeId, int expected, int actual, int delta) {}

    public boolean clean() { return drifted.isEmpty(); }
}
