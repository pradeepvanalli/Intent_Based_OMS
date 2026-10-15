package ai.intentoms.sourcing.config;

import ai.intentoms.platform.config.ConfigKeys;

/** Tenant-level runtime settings for sourcing and promising (read through {@code TenantConfigStore}). */
public final class SourcingConfigKeys {
    private SourcingConfigKeys() {}

    /** Contribution margin ratio floor, e.g. 0.12 (GR-01, FR-SRC-04). */
    public static final String MARGIN_FLOOR = ConfigKeys.MARGIN_FLOOR;
    /** Maximum packages for split policies (K). */
    public static final String MAX_SPLIT_PACKAGES = ConfigKeys.MAX_SPLIT_PACKAGES;
    /** USD per extra package beyond the first (P2 evidence: $10–$18 carrier cost). Falls back to the rate card's value. */
    public static final String SPLIT_PENALTY_USD = ConfigKeys.SPLIT_PENALTY_USD;
    /** Candidate limit N (default 100, FR-SRC-01). */
    public static final String CANDIDATE_LIMIT = ConfigKeys.CANDIDATE_LIMIT;
    /** Map channel -> policy used when the intent is unclear (FR-SRC-03). */
    public static final String CHANNEL_DEFAULT_POLICY = ConfigKeys.CHANNEL_DEFAULT_POLICY;
    /** Map {cost, time, soft, split} composite weights (section 9 rule 1: weights live in configuration). */
    public static final String WEIGHTS = "sourcing.weights";
    /** boolean: ETAs skip Saturday/Sunday. */
    public static final String BUSINESS_DAYS_ONLY = "sourcing.business_days_only";
    /** km: a BOPIS store within this radius is offered as a pickup alternative for ship-to-home orders (FR-SRC-04). */
    public static final String PICKUP_RADIUS_KM = "sourcing.pickup_radius_km";
    /** days: promise date offset when nothing is in stock anywhere. */
    public static final String BACKORDER_LEAD_DAYS = "sourcing.backorder_lead_days";
    /** Map orderType -> constraints {nodeTypes, capabilities, maxDistanceKm, allowBackorder} mirroring order-types.yaml {@code sourcing:}. */
    public static final String ORDER_TYPE_CONSTRAINTS = "sourcing.order_type_constraints";
    /** Penalty added to a node+sku on a store reject / timeout (FR-SRC-07). */
    public static final String REJECT_PENALTY = "sourcing.reject_penalty";

    public static final double DEFAULT_MARGIN_FLOOR = 0.12;
    public static final int DEFAULT_MAX_SPLIT = 3;
    public static final int DEFAULT_CANDIDATE_LIMIT = 100;
    public static final double DEFAULT_PICKUP_RADIUS_KM = 50.0;
    public static final int DEFAULT_BACKORDER_LEAD_DAYS = 14;
    public static final double DEFAULT_REJECT_PENALTY = 0.3;
    public static final double DEFAULT_W_COST = 0.4, DEFAULT_W_TIME = 0.25, DEFAULT_W_SOFT = 0.25, DEFAULT_W_SPLIT = 0.1;
}
