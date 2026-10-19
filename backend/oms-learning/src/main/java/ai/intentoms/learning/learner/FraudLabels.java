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

    /** Provisional when the label says so; labels written before the flag existed are judged by their kind. */
    static boolean provisional(Map<String, Object> label) {
        if (label.get("provisional") instanceof Boolean b) return b;
        return "delivered_no_chargeback".equals(String.valueOf(label.get("kind")));
    }
}
