package ai.intentoms.learning.model;

import java.time.Instant;

/** Per-tenant governance for one learner (requirements doc GV-01..GV-03, table {@code learner}). */
public record LearnerState(String learnerKey, AutonomyLevel autonomyLevel, boolean enabled, int maxPromotionsPerWeek,
                            Double maxStep, Instant lastRunAt, String updatedBy, Instant updatedAt) {
    public static LearnerState defaultFor(String learnerKey) {
        return new LearnerState(learnerKey, AutonomyLevel.L1_RECOMMEND, true, 1, null, null, "system", Instant.now());
    }
}
