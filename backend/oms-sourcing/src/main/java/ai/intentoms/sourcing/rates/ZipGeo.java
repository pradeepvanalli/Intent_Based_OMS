package ai.intentoms.sourcing.rates;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Built-in geocoder for distance-based rating (FR-SRC-01, FR-SRC-05): US ZIP3 prefix → centroid + state, state → centroid,
 * country → centroid. No network call, so the promise path stays deterministic and sub-millisecond. Unknown ZIPs fall
 * back to the state centroid; unknown states to the continental centre with one extra transit day.
 */
public final class ZipGeo {
    public static final double EARTH_RADIUS_KM = 6371.0088;
    /** Geographic centre of the contiguous United States (Lebanon, KS). */
    public static final GeoPoint CONTINENTAL_CENTRE = new GeoPoint(39.8283, -98.5795, null, GeoPoint.UNKNOWN);

    private static final Map<String, double[]> ZIP3 = new HashMap<>(300);
    private static final Map<String, String> ZIP3_STATE = new HashMap<>(300);
    private static final Map<String, double[]> STATES = new HashMap<>(60);
    private static final Map<String, double[]> COUNTRIES = new HashMap<>(16);

    // zip3,lat,lon,state — centroids of the ZIP3 prefix's main city (sectional centre facility).
    private static final String ZIP3_TABLE = """
        010,42.10,-72.59,MA 021,42.36,-71.06,MA 022,42.35,-71.05,MA 024,42.40,-71.10,MA 027,42.08,-71.02,MA 028,41.82,-71.41,RI 029,41.82,-71.41,RI
        030,42.99,-71.46,NH 031,42.99,-71.46,NH 040,43.66,-70.26,ME 041,43.66,-70.26,ME 050,43.65,-72.32,VT 054,44.48,-73.21,VT
        060,41.76,-72.69,CT 061,41.76,-72.69,CT 065,41.31,-72.92,CT 068,41.05,-73.54,CT 069,41.05,-73.54,CT
        070,40.74,-74.17,NJ 071,40.74,-74.17,NJ 072,40.66,-74.21,NJ 074,40.92,-74.17,NJ 076,40.89,-74.04,NJ 077,40.35,-74.07,NJ 078,40.88,-74.56,NJ
        080,39.93,-75.02,NJ 081,39.93,-75.12,NJ 085,40.22,-74.76,NJ 086,40.22,-74.76,NJ 087,39.95,-74.20,NJ 088,40.52,-74.41,NJ 089,40.49,-74.45,NJ
        100,40.75,-73.99,NY 101,40.75,-73.99,NY 102,40.75,-73.99,NY 103,40.58,-74.15,NY 104,40.84,-73.87,NY 105,41.03,-73.76,NY 106,41.03,-73.76,NY
        110,40.73,-73.79,NY 111,40.74,-73.93,NY 112,40.65,-73.95,NY 113,40.76,-73.83,NY 114,40.70,-73.79,NY 115,40.71,-73.62,NY 117,40.77,-73.52,NY
        120,42.65,-73.76,NY 122,42.65,-73.76,NY 130,43.05,-76.15,NY 132,43.05,-76.15,NY 140,42.89,-78.88,NY 142,42.89,-78.88,NY 146,43.16,-77.61,NY
        150,40.44,-79.99,PA 152,40.44,-79.99,PA 170,40.27,-76.88,PA 171,40.27,-76.88,PA 180,40.60,-75.49,PA 181,40.60,-75.49,PA
        190,39.95,-75.17,PA 191,39.95,-75.17,PA 193,40.04,-75.51,PA 194,40.12,-75.34,PA 197,39.74,-75.55,DE 198,39.74,-75.55,DE 199,39.16,-75.52,DE
        200,38.90,-77.04,DC 202,38.90,-77.04,DC 203,38.90,-77.04,DC 206,38.62,-76.94,MD 208,39.00,-77.10,MD 209,39.00,-77.02,MD 210,39.29,-76.61,MD 212,39.29,-76.61,MD 217,39.41,-77.41,MD
        220,38.80,-77.05,VA 221,38.85,-77.30,VA 222,38.88,-77.10,VA 230,37.54,-77.44,VA 232,37.54,-77.44,VA 234,36.85,-76.29,VA 235,36.85,-76.29,VA
        250,38.35,-81.63,WV 253,38.35,-81.63,WV 270,36.07,-79.79,NC 272,36.07,-79.79,NC 275,35.78,-78.64,NC 276,35.78,-78.64,NC 277,35.99,-78.90,NC 280,35.23,-80.84,NC 282,35.23,-80.84,NC
        290,34.00,-81.03,SC 292,34.00,-81.03,SC 294,32.78,-79.93,SC 296,34.85,-82.39,SC
        300,33.75,-84.39,GA 301,33.75,-84.39,GA 302,33.75,-84.39,GA 303,33.75,-84.39,GA 311,33.75,-84.39,GA 313,32.08,-81.10,GA
        320,30.33,-81.66,FL 322,30.33,-81.66,FL 327,28.54,-81.38,FL 328,28.54,-81.38,FL 329,28.08,-80.61,FL 330,25.77,-80.19,FL 331,25.77,-80.19,FL
        333,26.12,-80.14,FL 334,26.71,-80.05,FL 335,27.95,-82.46,FL 336,27.95,-82.46,FL 337,27.77,-82.64,FL 339,26.64,-81.87,FL 342,27.34,-82.53,FL
        350,33.52,-86.80,AL 352,33.52,-86.80,AL 360,32.37,-86.30,AL 366,30.69,-88.04,AL
        370,36.16,-86.78,TN 372,36.16,-86.78,TN 373,35.05,-85.31,TN 379,35.96,-83.92,TN 380,35.15,-90.05,TN 381,35.15,-90.05,TN
        390,32.30,-90.18,MS 392,32.30,-90.18,MS 400,38.25,-85.76,KY 402,38.25,-85.76,KY 405,38.04,-84.50,KY
        430,39.96,-83.00,OH 432,39.96,-83.00,OH 436,41.65,-83.54,OH 441,41.50,-81.69,OH 442,41.08,-81.52,OH 443,41.08,-81.52,OH 452,39.10,-84.51,OH 454,39.76,-84.19,OH
        460,39.77,-86.16,IN 462,39.77,-86.16,IN 466,41.59,-87.35,IN 468,41.08,-85.14,IN
        480,42.49,-83.14,MI 481,42.28,-83.74,MI 482,42.33,-83.05,MI 483,42.64,-83.29,MI 495,42.96,-85.67,MI
        500,41.59,-93.62,IA 503,41.59,-93.62,IA 522,41.98,-91.67,IA 530,43.04,-87.91,WI 532,43.04,-87.91,WI 537,43.07,-89.40,WI
        550,44.95,-93.09,MN 551,44.95,-93.09,MN 553,44.98,-93.27,MN 554,44.98,-93.27,MN 558,46.79,-92.10,MN
        570,43.55,-96.73,SD 571,43.55,-96.73,SD 580,46.88,-96.79,ND 581,46.88,-96.79,ND 590,45.78,-108.50,MT 591,45.78,-108.50,MT
        600,42.04,-87.98,IL 601,42.10,-87.90,IL 602,42.05,-87.69,IL 604,41.70,-87.80,IL 605,41.80,-88.10,IL 606,41.88,-87.63,IL 607,41.88,-87.63,IL 608,41.88,-87.63,IL 616,40.69,-89.59,IL 627,39.80,-89.64,IL
        630,38.63,-90.20,MO 631,38.63,-90.20,MO 640,39.10,-94.58,MO 641,39.10,-94.58,MO 660,39.11,-94.63,KS 662,39.11,-94.63,KS 672,37.69,-97.34,KS 680,41.26,-95.94,NE 681,41.26,-95.94,NE
        700,29.95,-90.07,LA 701,29.95,-90.07,LA 708,30.45,-91.19,LA 720,34.75,-92.29,AR 722,34.75,-92.29,AR 730,35.47,-97.52,OK 731,35.47,-97.52,OK 741,36.15,-95.99,OK
        750,32.78,-96.80,TX 751,32.78,-96.80,TX 752,32.78,-96.80,TX 753,32.78,-96.80,TX 760,32.76,-97.33,TX 761,32.76,-97.33,TX
        770,29.76,-95.37,TX 772,29.76,-95.37,TX 773,29.76,-95.37,TX 774,29.76,-95.37,TX 775,29.76,-95.37,TX 782,29.42,-98.49,TX 786,30.27,-97.74,TX 787,30.27,-97.74,TX
        791,35.22,-101.83,TX 794,33.58,-101.86,TX 799,31.76,-106.49,TX
        800,39.74,-104.98,CO 801,39.74,-104.98,CO 802,39.74,-104.98,CO 803,40.01,-105.27,CO 809,38.83,-104.82,CO 820,41.14,-104.82,WY 832,42.87,-112.45,ID 837,43.62,-116.21,ID
        840,40.76,-111.89,UT 841,40.76,-111.89,UT 850,33.45,-112.07,AZ 852,33.45,-112.07,AZ 853,33.45,-112.07,AZ 857,32.22,-110.97,AZ 870,35.08,-106.65,NM 871,35.08,-106.65,NM
        889,36.17,-115.14,NV 891,36.17,-115.14,NV 894,39.53,-119.81,NV 895,39.53,-119.81,NV
        900,34.05,-118.24,CA 901,34.05,-118.24,CA 902,33.96,-118.35,CA 903,33.96,-118.35,CA 904,34.02,-118.49,CA 905,33.84,-118.34,CA 906,33.98,-118.03,CA 907,33.77,-118.19,CA 908,33.77,-118.19,CA
        910,34.15,-118.14,CA 911,34.15,-118.14,CA 912,34.14,-118.25,CA 913,34.19,-118.45,CA 914,34.19,-118.45,CA 915,34.18,-118.31,CA 917,34.02,-117.96,CA 918,34.09,-118.13,CA
        919,32.72,-117.16,CA 920,32.72,-117.16,CA 921,32.72,-117.16,CA 922,33.83,-116.55,CA 923,34.11,-117.29,CA 924,34.11,-117.29,CA 925,33.95,-117.40,CA 926,33.75,-117.87,CA 927,33.75,-117.87,CA 928,33.84,-117.91,CA
        930,34.20,-119.18,CA 931,34.42,-119.70,CA 932,35.37,-119.02,CA 933,35.37,-119.02,CA 936,36.75,-119.77,CA 937,36.75,-119.77,CA 939,36.68,-121.66,CA
        940,37.77,-122.42,CA 941,37.77,-122.42,CA 943,37.44,-122.14,CA 945,37.80,-122.27,CA 946,37.80,-122.27,CA 947,37.87,-122.27,CA 948,37.94,-122.35,CA 949,37.97,-122.53,CA
        950,37.34,-121.89,CA 951,37.34,-121.89,CA 952,37.96,-121.29,CA 953,37.64,-120.99,CA 954,38.44,-122.71,CA 956,38.58,-121.49,CA 957,38.58,-121.49,CA 958,38.58,-121.49,CA
        967,21.31,-157.86,HI 968,21.31,-157.86,HI 970,45.52,-122.68,OR 972,45.52,-122.68,OR 973,44.94,-123.04,OR 974,44.05,-123.09,OR
        980,47.61,-122.33,WA 981,47.61,-122.33,WA 982,47.98,-122.20,WA 983,47.25,-122.44,WA 984,47.25,-122.44,WA 985,47.04,-122.90,WA 990,47.66,-117.43,WA 992,47.66,-117.43,WA
        995,61.22,-149.90,AK 997,64.84,-147.72,AK
        """;

    private static final String STATE_TABLE = """
        AL,32.81,-86.79 AK,61.37,-152.40 AZ,33.73,-111.43 AR,34.97,-92.37 CA,36.12,-119.68 CO,39.06,-105.31 CT,41.60,-72.76 DE,39.32,-75.51 DC,38.90,-77.03
        FL,27.77,-81.69 GA,33.04,-83.64 HI,21.09,-157.50 ID,44.24,-114.48 IL,40.35,-88.99 IN,39.85,-86.26 IA,42.01,-93.21 KS,38.53,-96.73 KY,37.67,-84.67
        LA,31.17,-91.87 ME,44.69,-69.38 MD,39.06,-76.80 MA,42.23,-71.53 MI,43.33,-84.54 MN,45.69,-93.90 MS,32.74,-89.68 MO,38.46,-92.29 MT,46.92,-110.45
        NE,41.13,-98.27 NV,38.31,-117.06 NH,43.45,-71.56 NJ,40.30,-74.52 NM,34.84,-106.25 NY,42.17,-74.95 NC,35.63,-79.81 ND,47.53,-99.78 OH,40.39,-82.76
        OK,35.57,-96.93 OR,44.57,-122.07 PA,40.59,-77.21 RI,41.68,-71.51 SC,33.86,-80.95 SD,44.30,-99.44 TN,35.75,-86.69 TX,31.05,-97.56 UT,40.15,-111.86
        VT,44.05,-72.71 VA,37.77,-78.17 WA,47.40,-121.49 WV,38.49,-80.95 WI,44.27,-89.62 WY,42.76,-107.30 PR,18.22,-66.59
        """;

    private static final String COUNTRY_TABLE = """
        MX,23.63,-102.55 CA,56.13,-106.35 GB,54.00,-2.00 DE,51.17,10.45 FR,46.23,2.21 ES,40.46,-3.75 IT,41.87,12.57 NL,52.13,5.29 IE,53.14,-7.69
        AU,-25.27,133.78 NZ,-40.90,174.89 JP,36.20,138.25 KR,35.91,127.77 CN,35.86,104.20 IN,20.59,78.96 BR,-14.24,-51.93 SG,1.35,103.82
        """;

    static {
        for (String row : ZIP3_TABLE.trim().split("\\s+")) {
            String[] p = row.split(",");
            ZIP3.put(p[0], new double[]{Double.parseDouble(p[1]), Double.parseDouble(p[2])});
            ZIP3_STATE.put(p[0], p[3]);
        }
        for (String row : STATE_TABLE.trim().split("\\s+")) {
            String[] p = row.split(",");
            STATES.put(p[0], new double[]{Double.parseDouble(p[1]), Double.parseDouble(p[2])});
        }
        for (String row : COUNTRY_TABLE.trim().split("\\s+")) {
            String[] p = row.split(",");
            COUNTRIES.put(p[0], new double[]{Double.parseDouble(p[1]), Double.parseDouble(p[2])});
        }
    }

    private ZipGeo() {}

    /** Resolves a US destination: ZIP3 centroid, else state centroid, else continental centre (+1 transit day). */
    public static GeoPoint locate(String zip, String state) { return locate(zip, state, "US"); }

    /**
     * Resolves a destination. Non-US countries resolve to the country centroid when known (precision {@code country}),
     * otherwise to the continental US centre with the unknown penalty.
     */
    public static GeoPoint locate(String zip, String state, String country) {
        String c = country == null || country.isBlank() ? "US" : country.trim().toUpperCase(Locale.ROOT);
        if (!"US".equals(c)) {
            double[] cc = COUNTRIES.get(c);
            return cc == null ? CONTINENTAL_CENTRE : new GeoPoint(cc[0], cc[1], state == null ? null : state.toUpperCase(Locale.ROOT), GeoPoint.COUNTRY);
        }
        String z3 = zip3(zip);
        if (z3 != null) {
            double[] p = ZIP3.get(z3);
            if (p != null) return new GeoPoint(p[0], p[1], ZIP3_STATE.get(z3), GeoPoint.ZIP3);
        }
        String st = state == null ? null : state.trim().toUpperCase(Locale.ROOT);
        if (st != null) {
            double[] p = STATES.get(st);
            if (p != null) return new GeoPoint(p[0], p[1], st, GeoPoint.STATE);
        }
        return CONTINENTAL_CENTRE;
    }

    /** First three digits of a US ZIP (ZIP+4 and whitespace tolerated); null when not a ZIP. */
    public static String zip3(String zip) {
        if (zip == null) return null;
        String digits = zip.trim();
        if (digits.length() < 3) return null;
        String z3 = digits.substring(0, 3);
        for (int i = 0; i < 3; i++) if (!Character.isDigit(z3.charAt(i))) return null;
        return z3;
    }

    public static boolean knownZip3(String zip) { String z = zip3(zip); return z != null && ZIP3.containsKey(z); }
    public static boolean knownState(String state) { return state != null && STATES.containsKey(state.toUpperCase(Locale.ROOT)); }
    public static int zip3Count() { return ZIP3.size(); }

    /** Haversine great-circle distance in km. */
    public static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_KM * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
