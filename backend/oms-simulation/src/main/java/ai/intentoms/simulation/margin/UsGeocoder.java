package ai.intentoms.simulation.margin;

import ai.intentoms.sourcing.rates.GeoPoint;
import ai.intentoms.sourcing.rates.ZipGeo;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Embedded US geocoder for CSV destinations (Margin Replay): the ~150 largest US cities and every state centroid, no
 * network call. Resolution order: city + state → the city; a known ZIP3 prefix → the ZIP3 centroid (via
 * {@link ZipGeo}); a known state → the state centroid. Non-US destinations resolve to empty so the caller can skip them
 * with a reason. State names ("New York") are accepted as well as codes ("NY").
 */
public final class UsGeocoder {
    /** @param precision city | zip3 | state */
    public record Location(double lat, double lon, String state, String city, String precision) {
        public double distanceKm(double lat2, double lon2) { return ZipGeo.haversineKm(lat, lon, lat2, lon2); }
    }

    private static final Set<String> US = Set.of("US", "USA", "UNITED STATES", "UNITED STATES OF AMERICA", "U.S.", "U.S.A.", "AMERICA");
    private static final Map<String, double[]> CITIES = new HashMap<>(200);
    private static final Map<String, double[]> STATES = new HashMap<>(60);
    private static final Map<String, String> STATE_NAMES = new HashMap<>(60);

    // city|ST|lat|lon — approximate city centres of the largest US cities (plus a few retail hubs).
    private static final String CITY_TABLE = """
        New York|NY|40.71|-74.01 Los Angeles|CA|34.05|-118.24 Chicago|IL|41.88|-87.63 Houston|TX|29.76|-95.37 Phoenix|AZ|33.45|-112.07 Philadelphia|PA|39.95|-75.17
        San Antonio|TX|29.42|-98.49 San Diego|CA|32.72|-117.16 Dallas|TX|32.78|-96.80 San Jose|CA|37.34|-121.89 Austin|TX|30.27|-97.74 Jacksonville|FL|30.33|-81.66
        Fort Worth|TX|32.76|-97.33 Columbus|OH|39.96|-83.00 Charlotte|NC|35.23|-80.84 Indianapolis|IN|39.77|-86.16 San Francisco|CA|37.77|-122.42 Seattle|WA|47.61|-122.33
        Denver|CO|39.74|-104.98 Washington|DC|38.90|-77.04 Nashville|TN|36.16|-86.78 Oklahoma City|OK|35.47|-97.52 El Paso|TX|31.76|-106.49 Boston|MA|42.36|-71.06
        Portland|OR|45.52|-122.68 Las Vegas|NV|36.17|-115.14 Detroit|MI|42.33|-83.05 Memphis|TN|35.15|-90.05 Louisville|KY|38.25|-85.76 Baltimore|MD|39.29|-76.61
        Milwaukee|WI|43.04|-87.91 Albuquerque|NM|35.08|-106.65 Tucson|AZ|32.22|-110.97 Fresno|CA|36.75|-119.77 Sacramento|CA|38.58|-121.49 Mesa|AZ|33.42|-111.83
        Kansas City|MO|39.10|-94.58 Atlanta|GA|33.75|-84.39 Omaha|NE|41.26|-95.94 Colorado Springs|CO|38.83|-104.82 Raleigh|NC|35.78|-78.64 Long Beach|CA|33.77|-118.19
        Virginia Beach|VA|36.85|-76.03 Miami|FL|25.77|-80.19 Oakland|CA|37.80|-122.27 Minneapolis|MN|44.98|-93.27 Tulsa|OK|36.15|-95.99 Bakersfield|CA|35.37|-119.02
        Wichita|KS|37.69|-97.34 Arlington|TX|32.74|-97.11 Aurora|CO|39.73|-104.83 Tampa|FL|27.95|-82.46 New Orleans|LA|29.95|-90.07 Cleveland|OH|41.50|-81.69
        Honolulu|HI|21.31|-157.86 Anaheim|CA|33.84|-117.91 Lexington|KY|38.04|-84.50 Stockton|CA|37.96|-121.29 Corpus Christi|TX|27.80|-97.40 Henderson|NV|36.04|-115.00
        Riverside|CA|33.95|-117.40 Newark|NJ|40.74|-74.17 Saint Paul|MN|44.95|-93.09 Santa Ana|CA|33.75|-117.87 Cincinnati|OH|39.10|-84.51 Irvine|CA|33.68|-117.83
        Orlando|FL|28.54|-81.38 Pittsburgh|PA|40.44|-79.99 St. Louis|MO|38.63|-90.20 Greensboro|NC|36.07|-79.79 Jersey City|NJ|40.73|-74.08 Anchorage|AK|61.22|-149.90
        Lincoln|NE|40.81|-96.68 Plano|TX|33.02|-96.70 Durham|NC|35.99|-78.90 Buffalo|NY|42.89|-78.88 Chandler|AZ|33.31|-111.84 Chula Vista|CA|32.64|-117.08
        Toledo|OH|41.65|-83.54 Madison|WI|43.07|-89.40 Gilbert|AZ|33.35|-111.79 Reno|NV|39.53|-119.81 Fort Wayne|IN|41.08|-85.14 North Las Vegas|NV|36.20|-115.12
        St. Petersburg|FL|27.77|-82.64 Lubbock|TX|33.58|-101.86 Irving|TX|32.81|-96.95 Laredo|TX|27.51|-99.51 Winston-Salem|NC|36.10|-80.24 Chesapeake|VA|36.77|-76.29
        Glendale|AZ|33.54|-112.19 Garland|TX|32.91|-96.64 Scottsdale|AZ|33.49|-111.93 Norfolk|VA|36.85|-76.29 Boise|ID|43.62|-116.21 Fremont|CA|37.55|-121.99
        Spokane|WA|47.66|-117.43 Santa Clarita|CA|34.39|-118.54 Baton Rouge|LA|30.45|-91.19 Richmond|VA|37.54|-77.44 Hialeah|FL|25.86|-80.28 San Bernardino|CA|34.11|-117.29
        Tacoma|WA|47.25|-122.44 Modesto|CA|37.64|-120.99 Huntsville|AL|34.73|-86.59 Des Moines|IA|41.59|-93.62 Yonkers|NY|40.93|-73.90 Rochester|NY|43.16|-77.61
        Moreno Valley|CA|33.94|-117.23 Fayetteville|NC|35.05|-78.88 Fontana|CA|34.09|-117.44 Columbus|GA|32.46|-84.99 Worcester|MA|42.26|-71.80 Port St. Lucie|FL|27.27|-80.35
        Little Rock|AR|34.75|-92.29 Augusta|GA|33.47|-81.97 Oxnard|CA|34.20|-119.18 Birmingham|AL|33.52|-86.80 Montgomery|AL|32.37|-86.30 Frisco|TX|33.15|-96.82
        Amarillo|TX|35.22|-101.83 Salt Lake City|UT|40.76|-111.89 Grand Rapids|MI|42.96|-85.67 Huntington Beach|CA|33.66|-117.99 Overland Park|KS|38.98|-94.67 Glendale|CA|34.14|-118.25
        Tallahassee|FL|30.44|-84.28 Grand Prairie|TX|32.75|-96.99 McKinney|TX|33.20|-96.64 Cape Coral|FL|26.56|-81.95 Sioux Falls|SD|43.55|-96.73 Peoria|AZ|33.58|-112.24
        Providence|RI|41.82|-71.41 Vancouver|WA|45.64|-122.66 Knoxville|TN|35.96|-83.92 Akron|OH|41.08|-81.52 Shreveport|LA|32.53|-93.75 Mobile|AL|30.69|-88.04
        Brownsville|TX|25.90|-97.50 Newport News|VA|37.09|-76.47 Fort Lauderdale|FL|26.12|-80.14 Chattanooga|TN|35.05|-85.31 Tempe|AZ|33.43|-111.94 Aurora|IL|41.76|-88.32
        Santa Rosa|CA|38.44|-122.71 Eugene|OR|44.05|-123.09 Elk Grove|CA|38.41|-121.37 Salem|OR|44.94|-123.04 Ontario|CA|34.06|-117.65 Cary|NC|35.79|-78.78
        Rancho Cucamonga|CA|34.11|-117.59 Oceanside|CA|33.20|-117.38 Lancaster|CA|34.69|-118.15 Garden Grove|CA|33.77|-117.94 Pembroke Pines|FL|26.00|-80.30 Fort Collins|CO|40.59|-105.08
        Palmdale|CA|34.58|-118.12 Springfield|MO|37.21|-93.29 Clarksville|TN|36.53|-87.36 Salinas|CA|36.68|-121.66 Hayward|CA|37.67|-122.08 Paterson|NJ|40.92|-74.17
        Alexandria|VA|38.80|-77.05 Macon|GA|32.84|-83.63 Corona|CA|33.88|-117.57 Kansas City|KS|39.11|-94.63 Lakewood|CO|39.70|-105.08 Springfield|MA|42.10|-72.59
        Sunnyvale|CA|37.37|-122.04 Jackson|MS|32.30|-90.18 Killeen|TX|31.12|-97.73 Hollywood|FL|26.01|-80.15 Murfreesboro|TN|35.85|-86.39 Pasadena|TX|29.69|-95.21
        Bellevue|WA|47.61|-122.20 Pomona|CA|34.06|-117.75 Escondido|CA|33.12|-117.09 Joliet|IL|41.53|-88.08 Charleston|SC|32.78|-79.93 Mesquite|TX|32.77|-96.60
        Naperville|IL|41.75|-88.15 Rockford|IL|42.27|-89.09 Bridgeport|CT|41.19|-73.20 Syracuse|NY|43.05|-76.15 Savannah|GA|32.08|-81.10 Roseville|CA|38.75|-121.29
        Torrance|CA|33.84|-118.34 Fullerton|CA|33.87|-117.92 Surprise|AZ|33.63|-112.37 McAllen|TX|26.20|-98.23 Thornton|CO|39.87|-104.97 Visalia|CA|36.33|-119.29
        Olathe|KS|38.88|-94.82 Gainesville|FL|29.65|-82.32 West Valley City|UT|40.69|-112.00 Orange|CA|33.79|-117.85 Denton|TX|33.21|-97.13 Warren|MI|42.49|-83.03
        Pasadena|CA|34.15|-118.14 Waco|TX|31.55|-97.15 Cedar Rapids|IA|41.98|-91.67 Dayton|OH|39.76|-84.19 Elizabeth|NJ|40.66|-74.21 Hampton|VA|37.03|-76.35
        Columbia|SC|34.00|-81.03 Kent|WA|47.38|-122.23 Stamford|CT|41.05|-73.54 Lakeland|FL|28.04|-81.95 Sterling Heights|MI|42.58|-83.03 Fargo|ND|46.88|-96.79
        Carrollton|TX|32.95|-96.89 Coral Springs|FL|26.27|-80.27 Hartford|CT|41.76|-72.69 Round Rock|TX|30.51|-97.68 Santa Clara|CA|37.35|-121.95 New Haven|CT|41.31|-72.92
        Brooklyn|NY|40.65|-73.95 Queens|NY|40.73|-73.79 Bronx|NY|40.84|-73.87 Staten Island|NY|40.58|-74.15 Manhattan|NY|40.78|-73.97 Cambridge|MA|42.37|-71.11
        Berkeley|CA|37.87|-122.27 Palo Alto|CA|37.44|-122.14 Santa Monica|CA|34.02|-118.49 Burbank|CA|34.18|-118.31 Boulder|CO|40.01|-105.27 Ann Arbor|MI|42.28|-83.74
        Edison|NJ|40.52|-74.41 Hoboken|NJ|40.74|-74.03 Arlington|VA|38.88|-77.10 Bethesda|MD|39.00|-77.10 Princeton|NJ|40.35|-74.66 Asheville|NC|35.60|-82.55
        Albany|NY|42.65|-73.76 Bend|OR|44.06|-121.32 Santa Barbara|CA|34.42|-119.70 Napa|CA|38.30|-122.29 Carlsbad|CA|33.16|-117.35 Scranton|PA|41.41|-75.66
        Burlington|VT|44.48|-73.21 Portland|ME|43.66|-70.26 Manchester|NH|42.99|-71.46 Wilmington|DE|39.74|-75.55 Charleston|WV|38.35|-81.63 Billings|MT|45.78|-108.50
        Cheyenne|WY|41.14|-104.82 Missoula|MT|46.87|-113.99 Santa Fe|NM|35.69|-105.94 Juneau|AK|58.30|-134.42 Hilo|HI|19.73|-155.09 San Juan|PR|18.47|-66.11
        """;

    private static final String STATE_TABLE = """
        AL|Alabama|32.81|-86.79 AK|Alaska|61.37|-152.40 AZ|Arizona|33.73|-111.43 AR|Arkansas|34.97|-92.37 CA|California|36.12|-119.68 CO|Colorado|39.06|-105.31
        CT|Connecticut|41.60|-72.76 DE|Delaware|39.32|-75.51 DC|District_of_Columbia|38.90|-77.03 FL|Florida|27.77|-81.69 GA|Georgia|33.04|-83.64 HI|Hawaii|21.09|-157.50
        ID|Idaho|44.24|-114.48 IL|Illinois|40.35|-88.99 IN|Indiana|39.85|-86.26 IA|Iowa|42.01|-93.21 KS|Kansas|38.53|-96.73 KY|Kentucky|37.67|-84.67 LA|Louisiana|31.17|-91.87
        ME|Maine|44.69|-69.38 MD|Maryland|39.06|-76.80 MA|Massachusetts|42.23|-71.53 MI|Michigan|43.33|-84.54 MN|Minnesota|45.69|-93.90 MS|Mississippi|32.74|-89.68
        MO|Missouri|38.46|-92.29 MT|Montana|46.92|-110.45 NE|Nebraska|41.13|-98.27 NV|Nevada|38.31|-117.06 NH|New_Hampshire|43.45|-71.56 NJ|New_Jersey|40.30|-74.52
        NM|New_Mexico|34.84|-106.25 NY|New_York|42.17|-74.95 NC|North_Carolina|35.63|-79.81 ND|North_Dakota|47.53|-99.78 OH|Ohio|40.39|-82.76 OK|Oklahoma|35.57|-96.93
        OR|Oregon|44.57|-122.07 PA|Pennsylvania|40.59|-77.21 RI|Rhode_Island|41.68|-71.51 SC|South_Carolina|33.86|-80.95 SD|South_Dakota|44.30|-99.44 TN|Tennessee|35.75|-86.69
        TX|Texas|31.05|-97.56 UT|Utah|40.15|-111.86 VT|Vermont|44.05|-72.71 VA|Virginia|37.77|-78.17 WA|Washington|47.40|-121.49 WV|West_Virginia|38.49|-80.95
        WI|Wisconsin|44.27|-89.62 WY|Wyoming|42.76|-107.30 PR|Puerto_Rico|18.22|-66.59
        """;

    static {
        for (String row : CITY_TABLE.trim().split("\\s*\\R\\s*|(?<=\\d)\\s+(?=[A-Z])")) {
            String[] p = row.trim().split("\\|");
            if (p.length < 4) continue;
            CITIES.put(cityKey(p[0], p[1]), new double[]{Double.parseDouble(p[2]), Double.parseDouble(p[3])});
        }
        for (String row : STATE_TABLE.trim().split("\\s+")) {
            String[] p = row.split("\\|");
            STATES.put(p[0], new double[]{Double.parseDouble(p[2]), Double.parseDouble(p[3])});
            STATE_NAMES.put(p[1].replace('_', ' ').toUpperCase(Locale.ROOT), p[0]);
        }
    }

    private UsGeocoder() {}

    public static int cityCount() { return CITIES.size(); }

    /** True for a blank country or any US spelling/code. */
    public static boolean isUs(String country) {
        if (country == null || country.isBlank()) return true;
        return US.contains(country.trim().toUpperCase(Locale.ROOT));
    }

    /** "NY" → NY, "New York" → NY, "ny " → NY; null when unknown. */
    public static String stateCode(String stateOrName) {
        if (stateOrName == null || stateOrName.isBlank()) return null;
        String s = stateOrName.trim().toUpperCase(Locale.ROOT);
        if (STATES.containsKey(s)) return s;
        String byName = STATE_NAMES.get(s.replaceAll("\\s+", " "));
        if (byName != null) return byName;
        return null;
    }

    /** Resolves a US destination; empty for a non-US country or when neither city, ZIP nor state is recognised. */
    public static Optional<Location> locate(String city, String state, String zip, String country) {
        if (!isUs(country)) return Optional.empty();
        String st = stateCode(state);
        if (st == null && zip != null) {
            GeoPoint z = ZipGeo.locate(zip, null, "US");
            if (GeoPoint.ZIP3.equals(z.precision())) st = z.state();
        }
        if (city != null && !city.isBlank() && st != null) {
            double[] c = CITIES.get(cityKey(city, st));
            if (c != null) return Optional.of(new Location(c[0], c[1], st, tidy(city), "city"));
        }
        if (zip != null && !zip.isBlank()) {
            GeoPoint z = ZipGeo.locate(zip, st, "US");
            if (GeoPoint.ZIP3.equals(z.precision())) return Optional.of(new Location(z.lat(), z.lon(), z.state() == null ? st : z.state(), tidy(city), "zip3"));
        }
        if (st != null) {
            double[] s = STATES.get(st);
            return Optional.of(new Location(s[0], s[1], st, tidy(city), "state"));
        }
        return Optional.empty();
    }

    static String cityKey(String city, String state) {
        String c = city.toLowerCase(Locale.ROOT).replace("saint ", "st. ").replace("st ", "st. ").replace("ft. ", "fort ").replace("ft ", "fort ").replaceAll("[^a-z]+", " ").trim();
        return c + "|" + state.trim().toUpperCase(Locale.ROOT);
    }

    private static String tidy(String city) { return city == null || city.isBlank() ? null : city.trim(); }
}
