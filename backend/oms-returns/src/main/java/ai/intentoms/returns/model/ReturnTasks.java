package ai.intentoms.returns.model;

/** Human task types and queues the returns module opens (T2/T3 gates of the PRD §8 tier table). */
public final class ReturnTasks {
    private ReturnTasks() {}
    /** Queue for abuse-risk reviews and denial recommendations. */
    public static final String QUEUE_RISK_REVIEW = "risk_review";
    /** Queue for refunds that need a finance approver (above threshold or refused by the GR-05 SpendGuard). */
    public static final String QUEUE_REFUND_APPROVALS = "refund_approvals";
    /** Queue for disposition questions the model could not answer. */
    public static final String QUEUE_RETURNS_OPS = "returns_ops";
    /** T3: deny a return; the model recommends, a person decides ({@code ReturnService.applyDenial}). */
    public static final String TYPE_RETURN_DENIAL = "return_denial";
    /** T2: refund timing could not be chosen autonomously ({@code ReturnService.applyReview}). */
    public static final String TYPE_RETURN_REVIEW = "return_review";
    /** T3: money-moving refund needing approval ({@code ReturnService.approveRefund}). */
    public static final String TYPE_REFUND_APPROVAL = "refund_approval";
    /** T1 disposition the model escalated. */
    public static final String TYPE_DISPOSITION_REVIEW = "return_disposition";
}
