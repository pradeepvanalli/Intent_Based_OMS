package ai.intentoms.learning.model;

import java.time.Instant;

/** A typed, measured result attached to an episode once it's known (requirements doc OC-03, table {@code outcome}). */
public record OutcomeRecord(String outcomeId, String episodeId, String type, OutcomeData payload, Instant observedAt, String sourceEventId, Instant createdAt) {
    public static OutcomeRecord of(String outcomeId, String episodeId, OutcomeData payload, Instant observedAt, String sourceEventId, Instant createdAt) {
        return new OutcomeRecord(outcomeId, episodeId, payload.getClass().getSimpleName(), payload, observedAt, sourceEventId, createdAt);
    }
}
