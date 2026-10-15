package ai.intentoms.returns.model;

import java.util.Set;

/** Refund timing vocabulary chosen by the risk decision (FR-RET-02). Strings because the state model guards compare text. */
public final class RefundTiming {
    private RefundTiming() {}
    public static final String INSTANT = "instant";
    public static final String ON_CARRIER_SCAN = "on_carrier_scan";
    public static final String ON_INSPECTION = "on_inspection";
    public static final String STORE_CREDIT_ONLY = "store_credit_only";
    public static final String ESCALATE = "escalate";
    /** Timings a system or human may actually apply (escalate is a pending state, not a timing). */
    public static final Set<String> ACTIONABLE = Set.of(INSTANT, ON_CARRIER_SCAN, ON_INSPECTION, STORE_CREDIT_ONLY);
}
