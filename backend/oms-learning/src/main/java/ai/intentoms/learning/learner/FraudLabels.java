package ai.intentoms.learning.learner;

import ai.intentoms.decision.model.DecisionRecord;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Reading {@code fraud_outcome} labels the way every fraud learner must (defect 12). A provisional label ("delivered,
 * no chargeback yet", or a reviewer's "not fraud" before a chargeback could arrive) is a guess until the chargeback
 * window has passed: inside the window it is not ground truth, so learners skip it. Confirmed labels (a fraud
 * chargeback, a won chargeback, a refused delivery, a reviewer confirming fraud) count at once.
 */
public final class FraudLabels {
    public static final String LABEL = "fraud_outcome";
    /** Tenant config key: days a card payment can still be charged back (default 120). */
    public static final String WINDOW_KEY = "learning.fraud.chargeback_window_days";
    public static final int DEFAULT_WINDOW_DAYS = 120;
    /** Tenant config key: days for a refused delivery or a return grading to arrive (rto_risk, return_risk; default 30). */
    public static final String OUTCOME_WINDOW_KEY = "learning.fraud.outcome_window_days";
    public static final int DEFAULT_OUTCOME_WINDOW_DAYS = 30;

    private FraudLabels() {}

    /** The fraud outcome a learner may train on: true, false, or null when unlabelled or still provisional. */
    @SuppressWarnings("unchecked")
    public static Boolean usable(DecisionRecord r, Instant now, int windowDays) {
        Object o = r.labels() == null ? null : r.labels().get(LABEL);
        if (!(o instanceof Map<?, ?> m) || !(((Map<String, Object>) m).get("confirmed") instanceof Boolean fraud)) return null;
        if (provisional((Map<String, Object>) m)) {
            Instant decidedAt = r.createdAt() == null ? now : r.createdAt();
            if (decidedAt.plus(Duration.ofDays(Math.max(0, windowDays))).isAfter(now)) return null;
        }
        return fraud;
    }

    /**
     * The observation window of a fraud decision: card payments ({@code payment_risk}, {@code address_risk}) can be
     * charged back for the chargeback window; a refused delivery or a return grading arrives within the outcome window.
     */
    public static int windowDays(ai.intentoms.platform.config.TenantConfigStore config, String decision) {
        return "rto_risk".equals(decision) || "return_risk".equals(decision)
            ? config.getInt(OUTCOME_WINDOW_KEY, DEFAULT_OUTCOME_WINDOW_DAYS)
            : config.getInt(WINDOW_KEY, DEFAULT_WINDOW_DAYS);
    }

    /** The newest decision time a sample may include: decisions made before it have had the whole window to show fraud. */
    public static Instant cohortEnd(Instant now, int windowDays) { return now.minus(Duration.ofDays(Math.max(0, windowDays))); }

    /**
     * FD-03 / FD-01: the outcome of a decision for an unbiased sample. Frauds surface inside the window (a chargeback, a
     * refused parcel) while goods become ground truth only when it closes, so counting recent frauds next to older goods
     * inflates the fraud rate (on a young tenant every usable label is a fraud). A sample therefore takes frauds and
     * goods from the same observation window, decisions at least {@code windowDays} old, and never counts a label that is
     * still provisional. Null when the decision is too recent, unlabelled or provisional.
     */
    public static Boolean sampled(DecisionRecord r, Instant now, int windowDays) {
        if (r.createdAt() == null || r.createdAt().isAfter(cohortEnd(now, windowDays))) return null;
        return usable(r, now, windowDays);
    }

    /** Provisional when the label says so; labels written before the flag existed are judged by their kind. */
    static boolean provisional(Map<String, Object> label) {
        if (label.get("provisional") instanceof Boolean b) return b;
        return "delivered_no_chargeback".equals(String.valueOf(label.get("kind")));
    }
}
