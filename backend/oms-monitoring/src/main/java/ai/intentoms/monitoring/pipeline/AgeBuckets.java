package ai.intentoms.monitoring.pipeline;

import java.time.Duration;
import java.util.List;

/**
 * Age buckets of the pipeline heat-map (FR-MON-01, UI contract item 8): {@code <1h, 1-4h, 4-24h, 1-3d, >3d}.
 * The same boundaries are used in Java and in SQL so cells and totals always agree.
 */
public final class AgeBuckets {
    public static final String LT_1H = "<1h";
    public static final String H1_4 = "1-4h";
    public static final String H4_24 = "4-24h";
    public static final String D1_3 = "1-3d";
    public static final String GT_3D = ">3d";
    public static final List<String> ORDER = List.of(LT_1H, H1_4, H4_24, D1_3, GT_3D);

    private static final long HOUR = 3600;

    private AgeBuckets() {}

    /** Bucket for an age; negative ages (clock skew) fall into the youngest bucket. */
    public static String bucket(Duration age) { return bucket(age == null ? 0 : age.getSeconds()); }

    public static String bucket(long ageSeconds) {
        if (ageSeconds < HOUR) return LT_1H;
        if (ageSeconds < 4 * HOUR) return H1_4;
        if (ageSeconds < 24 * HOUR) return H4_24;
        if (ageSeconds < 72 * HOUR) return D1_3;
        return GT_3D;
    }

    /** SQL {@code CASE} expression producing the bucket label from an age-in-seconds expression. */
    public static String sqlCase(String ageSecondsExpr) {
        return "case when " + ageSecondsExpr + " < 3600 then '<1h'"
            + " when " + ageSecondsExpr + " < 14400 then '1-4h'"
            + " when " + ageSecondsExpr + " < 86400 then '4-24h'"
            + " when " + ageSecondsExpr + " < 259200 then '1-3d'"
            + " else '>3d' end";
    }
}
