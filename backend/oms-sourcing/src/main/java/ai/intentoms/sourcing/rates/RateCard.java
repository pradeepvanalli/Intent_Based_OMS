package ai.intentoms.sourcing.rates;

import ai.intentoms.finance.model.Money;
import ai.intentoms.platform.error.ValidationException;
import ai.intentoms.platform.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Carrier rate card (aggregator style, {@code config/tenants/demo/shipping-rates.yaml}): per service a list of distance
 * zones, each with nominal transit days and cost by weight band (band key = max kg); plus the default split penalty and
 * the on-time probability by transit days (P3: a probability, not a static table alone). Immutable; parsed once per tenant
 * and cached (FR-SRC-02, FR-SRC-05).
 */
public final class RateCard {
    public static final String PICKUP = "pickup";

    /** One quote: carrier cost and nominal transit days. */
    public record Quote(Money cost, int transitDays, String service, int zoneIndex, double weightBandKg) {}

    public record Zone(double maxKm, int transitDays, TreeMap<Double, Double> rates) {
        public double rateFor(double weightKg) {
            Map.Entry<Double, Double> e = rates.ceilingEntry(Math.max(0.0, weightKg));
            return e != null ? e.getValue() : rates.lastEntry().getValue();
        }
    }

    public record Service(String code, List<Zone> zones) {
        public Zone zoneFor(double distanceKm) {
            for (Zone z : zones) if (distanceKm <= z.maxKm()) return z;
            return zones.get(zones.size() - 1);
        }
    }

    private final Map<String, Service> services;
    private final double splitPenaltyUsd;
    private final TreeMap<Integer, Double> onTimeByTransitDays;
    private final String currency;
    private final Map<String, Object> source;

    public RateCard(Map<String, Service> services, double splitPenaltyUsd, Map<Integer, Double> onTimeByTransitDays, String currency, Map<String, Object> source) {
        if (services == null || services.isEmpty()) throw new ValidationException("Rate card must define at least one service");
        this.services = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(services));
        this.splitPenaltyUsd = splitPenaltyUsd;
        this.onTimeByTransitDays = new TreeMap<>(onTimeByTransitDays == null || onTimeByTransitDays.isEmpty() ? Map.of(0, 0.9) : onTimeByTransitDays);
        this.currency = currency == null ? "USD" : currency;
        this.source = source == null ? Map.of() : Map.copyOf(source);
    }

    /** Parses the YAML / JSON document ({@code services[]}, {@code splitPenaltyUsd}, {@code onTimeByTransitDays}). */
    @SuppressWarnings("unchecked")
    public static RateCard fromMap(Map<String, Object> doc) {
        if (doc == null || !(doc.get("services") instanceof List<?> list) || list.isEmpty()) throw new ValidationException("shipping-rates must contain a non-empty 'services' list");
        Map<String, Service> services = new LinkedHashMap<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> sm)) throw new ValidationException("service entries must be maps");
            String code = String.valueOf(sm.get("code")).toLowerCase(Locale.ROOT);
            if (!(sm.get("zones") instanceof List<?> zl) || zl.isEmpty()) throw new ValidationException("service '" + code + "' must define zones");
            List<Zone> zones = new ArrayList<>();
            for (Object zo : zl) {
                Map<String, Object> zm = (Map<String, Object>) zo;
                TreeMap<Double, Double> rates = new TreeMap<>();
                Object rm = zm.get("rates");
                if (!(rm instanceof Map<?, ?> ratesMap) || ratesMap.isEmpty()) throw new ValidationException("zone in service '" + code + "' must define rates");
                ratesMap.forEach((k, v) -> rates.put(Double.parseDouble(String.valueOf(k)), ((Number) v).doubleValue()));
                zones.add(new Zone(((Number) zm.getOrDefault("maxKm", 99999)).doubleValue(), ((Number) zm.getOrDefault("transitDays", 3)).intValue(), rates));
            }
            zones.sort((a, b) -> Double.compare(a.maxKm(), b.maxKm()));
            services.put(code, new Service(code, List.copyOf(zones)));
        }
        double split = doc.get("splitPenaltyUsd") instanceof Number n ? n.doubleValue() : 12.0;
        Map<Integer, Double> onTime = new TreeMap<>();
        if (doc.get("onTimeByTransitDays") instanceof Map<?, ?> om) om.forEach((k, v) -> onTime.put(Integer.parseInt(String.valueOf(k)), ((Number) v).doubleValue()));
        String currency = doc.get("currency") == null ? "USD" : String.valueOf(doc.get("currency"));
        return new RateCard(services, split, onTime, currency, doc);
    }

    public static RateCard fromYaml(String yaml) { return fromMap(Json.yamlMap(yaml)); }

    public Map<String, Object> source() { return source; }
    public String currency() { return currency; }
    public double splitPenaltyUsd() { return splitPenaltyUsd; }
    public List<String> services() { return List.copyOf(services.keySet()); }
    public boolean hasService(String code) { return code != null && services.containsKey(code.toLowerCase(Locale.ROOT)); }
    public Map<Integer, Double> onTimeTable() { return Map.copyOf(onTimeByTransitDays); }

    /**
     * Quotes a service for a distance and parcel weight. {@code pickup} is free with zero transit; an unknown service
     * falls back to {@code ground} (or the first service) so a mis-typed method never fails sourcing.
     */
    public Quote quote(String service, double distanceKm, double weightKg) {
        String code = service == null ? "" : service.toLowerCase(Locale.ROOT);
        if (PICKUP.equals(code)) return new Quote(Money.zero(currency), 0, PICKUP, -1, 0);
        Service s = services.get(code);
        if (s == null) s = services.getOrDefault("ground", services.values().iterator().next());
        Zone z = s.zoneFor(Math.max(0, distanceKm));
        Map.Entry<Double, Double> band = z.rates().ceilingEntry(Math.max(0.0, weightKg));
        if (band == null) band = z.rates().lastEntry();
        return new Quote(Money.of(band.getValue(), currency), z.transitDays(), s.code(), s.zones().indexOf(z), band.getKey());
    }

    /** Nominal transit days for a service at a distance (no weight dependence). */
    public int transitDays(String service, double distanceKm) { return quote(service, distanceKm, 0.5).transitDays(); }

    /**
     * On-time probability: table value for the largest transit-days key ≤ {@code transitDays} (beyond the last key the
     * last value decays 0.02 per extra day, floor 0.5) multiplied by {@code nodeReliability}, clamped to [0, 1].
     */
    public double onTimeProbability(int transitDays, double nodeReliability) {
        Map.Entry<Integer, Double> e = onTimeByTransitDays.floorEntry(Math.max(0, transitDays));
        double base;
        if (e != null) {
            base = e.getValue();
            int last = onTimeByTransitDays.lastKey();
            if (transitDays > last) base = Math.max(0.5, base - 0.02 * (transitDays - last));
        } else base = onTimeByTransitDays.firstEntry().getValue();
        return clamp(base * clamp(nodeReliability));
    }

    static double clamp(double v) { return Math.max(0.0, Math.min(1.0, v)); }
}
