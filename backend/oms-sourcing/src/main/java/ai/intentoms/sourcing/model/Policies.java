package ai.intentoms.sourcing.model;

import java.util.List;
import java.util.Set;

/** Fulfilment policy names (FR-SRC-03, FR-CAP-06 intent vocabulary). */
public final class Policies {
    private Policies() {}
    public static final String SHIP_COMPLETE = "ship_complete";
    public static final String SHIP_AS_AVAILABLE = "ship_as_available";
    public static final String SPLIT_UP_TO_K = "split_up_to_k";
    public static final String PICKUP_CONSOLIDATED = "pickup_consolidated";
    public static final String EARLIEST_PARTIAL = "earliest_partial";
    public static final String UNCLEAR = "unclear";

    public static final List<String> ALL = List.of(SHIP_COMPLETE, SHIP_AS_AVAILABLE, SPLIT_UP_TO_K, PICKUP_CONSOLIDATED, EARLIEST_PARTIAL);
    /** Policies that may produce more than one package. */
    public static final Set<String> SPLITTING = Set.of(SHIP_AS_AVAILABLE, SPLIT_UP_TO_K, EARLIEST_PARTIAL);

    public static boolean known(String p) { return p != null && ALL.contains(p); }
    public static boolean splitting(String p) { return p != null && SPLITTING.contains(p); }
}
