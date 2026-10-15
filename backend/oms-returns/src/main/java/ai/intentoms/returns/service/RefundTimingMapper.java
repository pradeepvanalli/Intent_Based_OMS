package ai.intentoms.returns.service;

import ai.intentoms.returns.model.RefundTiming;

/**
 * Maps the {@code return_risk} Score (0–4) to a refund timing (FR-RET-02) under the T2 autonomy rule of the PRD tier
 * table: the system acts on the model's score only in the ACT band; in any other band the conservative
 * {@code on_inspection} timing applies, unless the runtime escalated to a human, in which case the RMA waits
 * ({@code escalate}) for the review task.
 */
public final class RefundTimingMapper {
    private RefundTimingMapper() {}

    /** Score → timing when the decision acts: &lt;1 instant, 1–2 on carrier scan, 2–3 on inspection, ≥3 store credit only. */
    public static String byScore(double score) {
        if (score < 1.0) return RefundTiming.INSTANT;
        if (score < 2.0) return RefundTiming.ON_CARRIER_SCAN;
        if (score < 3.0) return RefundTiming.ON_INSPECTION;
        return RefundTiming.STORE_CREDIT_ONLY;
    }

    /**
     * @param acts          {@code DecisionResult.acts()}: ACT band and no human required
     * @param requiresHuman the runtime escalated (CONFIRM/ESCALATE on T2 without an accepted L2 proposal)
     */
    public static String map(double score, boolean acts, boolean requiresHuman) {
        if (acts) return byScore(score);
        if (requiresHuman) return RefundTiming.ESCALATE;
        return RefundTiming.ON_INSPECTION;
    }

    /** Risk level label shown to CS (never to the customer, GR-15). */
    public static String level(double score) {
        if (score < 1.0) return "low";
        if (score < 2.0) return "medium";
        if (score < 3.0) return "high";
        return "severe";
    }
}
