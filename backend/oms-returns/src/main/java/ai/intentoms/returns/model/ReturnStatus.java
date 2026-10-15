package ai.intentoms.returns.model;

import java.util.Set;

/** RMA statuses; identical to the state ids of the {@code return.standard} state model (FR-SM-01). */
public final class ReturnStatus {
    private ReturnStatus() {}
    public static final String REQUESTED = "requested";
    public static final String APPROVED = "approved";
    public static final String AWAITING_RECEIPT = "awaiting_receipt";
    public static final String RECEIVED = "received";
    public static final String INSPECTED = "inspected";
    public static final String REFUNDED = "refunded";
    public static final String REJECTED = "rejected";
    public static final String CANCELLED = "cancelled";
    public static final String KEEP_IT_REFUNDED = "keep_it_refunded";
    public static final Set<String> TERMINAL = Set.of(REFUNDED, REJECTED, CANCELLED, KEEP_IT_REFUNDED);
    /** Statuses whose returned quantity still counts against what may be returned again. */
    public static final Set<String> COUNTS_AS_RETURNED = Set.of(REQUESTED, APPROVED, AWAITING_RECEIPT, RECEIVED, INSPECTED, REFUNDED, KEEP_IT_REFUNDED);
}
