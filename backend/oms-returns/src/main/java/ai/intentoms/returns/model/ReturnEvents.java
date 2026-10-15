package ai.intentoms.returns.model;

/**
 * Ledger event types written on aggregate {@code return/<rmaId>} (correlationId = orderId). Grammar
 * {@code <aggregate>.<past_tense>} per ARCHITECTURE §2.
 */
public final class ReturnEvents {
    private ReturnEvents() {}
    public static final String REQUESTED = "return.requested";
    public static final String ELIGIBILITY_DECIDED = "return.eligibility_decided";
    public static final String REVIEW_REQUESTED = "return.review_requested";
    public static final String KEEP_IT_OFFERED = "return.keep_it_offered";
    public static final String CARRIER_SCANNED = "return.carrier_scanned";
    public static final String RECEIVED = "return.received";
    public static final String INSPECTED = "return.inspected";
    public static final String DISPOSITIONED = "return.dispositioned";
    public static final String REFUND_HELD = "return.refund_held";
    public static final String REFUND_APPROVED = "return.refund_approved";
    public static final String REFUNDED = "return.refunded";
    public static final String REFUND_FAILED = "return.refund_failed";
    public static final String REJECTED = "return.rejected";
    public static final String CANCELLED = "return.cancelled";
    public static final String STORE_CREDIT_ISSUED = "store_credit.issued";
}
