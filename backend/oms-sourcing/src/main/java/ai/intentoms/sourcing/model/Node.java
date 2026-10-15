package ai.intentoms.sourcing.model;

import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A fulfilment node (PRD section 10 "Node", FR-SRC-01, FR-SRC-08): distribution centre, store, vendor or 3PL.
 * {@code health} carries the live signals that feed {@code sourcing_node_score} (backlog, pick_rate, reject_rate,
 * staffing, weather, carrier_capacity); {@code costProfile} feeds the economics (labour_per_unit, packaging, handling);
 * {@code landedCost} may override the customs origin ({@code originCountry}). {@code penalty} is the learned,
 * decayed node-level penalty (FR-SRC-07) and is never part of the stored definition.
 */
public record Node(
    String id,
    String name,
    String type,
    double lat,
    double lon,
    String country,
    String state,
    String zip,
    Set<String> capabilities,
    int dailyCapacity,
    LocalTime cutoff,
    String timezone,
    Map<String, Object> health,
    Map<String, Object> costProfile,
    Map<String, Object> landedCost,
    boolean active,
    double penalty
) {
    public static final String TYPE_DC = "dc";
    public static final String TYPE_STORE = "store";
    public static final String TYPE_VENDOR = "vendor";
    public static final String TYPE_3PL = "3pl";

    public static final String CAP_SHIP = "ship";
    public static final String CAP_BOPIS = "bopis";
    public static final String CAP_SFS = "sfs";
    public static final String CAP_HAZMAT = "hazmat";
    public static final String CAP_SAME_DAY = "same_day";

    public Node {
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        health = health == null ? Map.of() : Map.copyOf(health);
        costProfile = costProfile == null ? Map.of() : Map.copyOf(costProfile);
        landedCost = landedCost == null ? Map.of() : Map.copyOf(landedCost);
        timezone = timezone == null || timezone.isBlank() ? "UTC" : timezone;
        country = country == null ? "US" : country.toUpperCase();
        penalty = Math.max(0.0, Math.min(1.0, penalty));
    }

    public boolean has(String capability) { return capabilities.contains(capability); }

    /** Whether the node can dispatch parcels (a DC/3PL/vendor with {@code ship} or a store with {@code sfs}). */
    public boolean canShip() { return has(CAP_SHIP) || has(CAP_SFS); }

    public double healthNumber(String key, double dflt) {
        Object v = health.get(key);
        return v instanceof Number n ? n.doubleValue() : dflt;
    }

    public double costNumber(String key, double dflt) {
        Object v = costProfile.get(key);
        return v instanceof Number n ? n.doubleValue() : dflt;
    }

    public double rejectRate() { return healthNumber("reject_rate", 0.0); }
    public double staffing() { return healthNumber("staffing", 1.0); }
    public int backlogUnits() { return (int) healthNumber("backlog", 0); }

    /** Node reliability used in on-time probability: {@code (1 - reject_rate)} (FR-SRC-02, FR-SRC-08). */
    public double reliability() { return Math.max(0.0, Math.min(1.0, 1.0 - rejectRate())); }

    /** Customs origin: {@code landedCost.originCountry} when set, else the node's country (FR-FIN-05). */
    public String customsOrigin() {
        Object o = landedCost.get("originCountry");
        return o == null ? country : o.toString().toUpperCase();
    }

    public Node withPenalty(double p) { return new Node(id, name, type, lat, lon, country, state, zip, capabilities, dailyCapacity, cutoff, timezone, health, costProfile, landedCost, active, p); }
    public Node withHealth(Map<String, Object> h) { return new Node(id, name, type, lat, lon, country, state, zip, capabilities, dailyCapacity, cutoff, timezone, h, costProfile, landedCost, active, penalty); }
    public Node withActive(boolean a) { return new Node(id, name, type, lat, lon, country, state, zip, capabilities, dailyCapacity, cutoff, timezone, health, costProfile, landedCost, a, penalty); }

    /** Merges new health signals over the existing ones (FR-SRC-08 hook). */
    public Node mergeHealth(Map<String, Object> signals) {
        Map<String, Object> merged = new LinkedHashMap<>(health);
        if (signals != null) merged.putAll(signals);
        return withHealth(merged);
    }
}
