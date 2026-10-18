package ai.intentoms.learning.model;

import java.time.Instant;

/**
 * Learned knowledge, stored as a typed fact with provenance (requirements doc §6.2/§9.1). Versioned; never updated
 * in place — a learner writes a new row with a later version and closes the previous one's {@code validTo}.
 */
public record LearnedFact(String factId, SubjectType subjectType, String subjectId, FactKey factKey, TypedValue value,
                           double confidence, int sampleSize, String learner, int version, Instant validFrom, Instant validTo, Instant createdAt) {
    public boolean activeAt(Instant t) { return !t.isBefore(validFrom) && (validTo == null || t.isBefore(validTo)); }
}
