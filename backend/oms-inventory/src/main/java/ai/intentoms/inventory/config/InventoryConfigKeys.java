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
    /** minutes, default unset: TTL for a cart reservation whose caller passes none; unset means no expiry (IU-02 static value). */
    public static final String RESERVATION_TTL_MINUTES = "inventory.reservation_ttl_minutes";
    /** days, default 90: on-hand older than this with no recent sale is reported stranded (IU-03). */
    public static final String STRANDED_DAYS = "inventory.stranded_days";
    /** days, default 30: stock at least this old gets a {@code STOCK_AGE_DAYS} fact the sourcing tie-break reads (IU-03). */
    public static final String AGED_DAYS = "inventory.aged_days";
}
