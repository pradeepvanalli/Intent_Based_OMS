package ai.intentoms.sourcing.rates;

/**
 * A resolved location. {@code precision} says how it was resolved: {@code zip3} (ZIP prefix centroid), {@code state}
 * (state centroid), {@code country} (country centroid) or {@code unknown} (continental US centre, +1 transit day).
 */
public record GeoPoint(double lat, double lon, String state, String precision) {
    public static final String ZIP3 = "zip3";
    public static final String STATE = "state";
    public static final String COUNTRY = "country";
    public static final String UNKNOWN = "unknown";

    /** Extra transit days to add because the location is only coarsely known. */
    public int transitPenaltyDays() { return UNKNOWN.equals(precision) ? 1 : 0; }

    /** Great-circle distance in km to another point (haversine, R = 6371.0088 km). */
    public double distanceKm(double otherLat, double otherLon) { return ZipGeo.haversineKm(lat, lon, otherLat, otherLon); }
}
