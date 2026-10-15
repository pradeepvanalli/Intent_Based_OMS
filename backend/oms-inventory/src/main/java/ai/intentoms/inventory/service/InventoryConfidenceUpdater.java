package ai.intentoms.inventory.service;

/**
 * Hook for FR-INV-05: a scoring job (Jev, using cycle-count age, recent rejects and shrink) writes a confidence per
 * sku and node here; sourcing reads it back through {@code Atp.confidence}.
 */
public interface InventoryConfidenceUpdater {
    /** Sets the on-hand confidence (0..1) for a sku at a node; writes {@code inventory.confidence_updated}. */
    void set(String sku, String nodeId, double confidence);
}
