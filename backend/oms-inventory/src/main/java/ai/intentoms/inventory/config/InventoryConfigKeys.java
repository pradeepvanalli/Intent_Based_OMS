package ai.intentoms.inventory.config;

/** Tenant-level runtime settings for the inventory module (read through {@code TenantConfigStore}). */
public final class InventoryConfigKeys {
    private InventoryConfigKeys() {}
    /** boolean, default false: count po/asn/in_transit supply towards ATP (FR-INV-01). */
    public static final String INCLUDE_INBOUND = "inventory.include_inbound";
    /** fraction 0..1, default 0: share of network ATP withheld from channel feeds to prevent oversell (FR-INV-03). */
    public static final String CHANNEL_BUFFER = "inventory.channel_buffer";
    /** units, default 0: |delta| above which reconciliation reports drift (FR-INV-04). */
    public static final String DRIFT_TOLERANCE = "inventory.drift_tolerance";
}
