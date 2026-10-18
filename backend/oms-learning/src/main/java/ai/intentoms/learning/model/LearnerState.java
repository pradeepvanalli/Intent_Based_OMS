package ai.intentoms.learning.model;

import java.time.Instant;

/**
 * Per-tenant governance for one learner (requirements doc GV-01..GV-03, table {@code learner}). {@code pausedReason}
 * is set while the learner is paused (kill switch, coverage breach, drift); pausing never changes the autonomy level.
 */
public record LearnerState(String learnerKey, AutonomyLevel autonomyLevel, boolean enabled, int maxPromotionsPerWeek,
                            Double maxStep, Instant lastRunAt, String updatedBy, Instant updatedAt, String pausedReason) {
    public LearnerState(String learnerKey, AutonomyLevel autonomyLevel, boolean enabled, int maxPromotionsPerWeek,
                        Double maxStep, Instant lastRunAt, String updatedBy, Instant updatedAt) {
        this(learnerKey, autonomyLevel, enabled, maxPromotionsPerWeek, maxStep, lastRunAt, updatedBy, updatedAt, null);
    }

    public static LearnerState defaultFor(String learnerKey) { return defaultFor(learnerKey, AutonomyLevel.L1_RECOMMEND); }

    public static LearnerState defaultFor(String learnerKey, AutonomyLevel level) {
        return new LearnerState(learnerKey, level, true, 1, null, null, "system", Instant.now(), null);
    }

    public boolean paused() { return pausedReason != null; }
}
