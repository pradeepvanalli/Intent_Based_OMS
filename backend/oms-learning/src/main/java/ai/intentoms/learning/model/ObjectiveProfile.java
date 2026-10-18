package ai.intentoms.learning.model;

import java.time.Instant;
import java.util.Map;

/** Tenant-weighted reward terms, versioned like any policy (requirements doc §7, table {@code objective_profile}). */
public record ObjectiveProfile(int version, Status status, ObjectiveWeights weights, Map<String, Object> constraints,
                                String approvedBy, Instant approvedAt, String createdBy, Instant createdAt) {
    public enum Status { DRAFT, APPROVED, RETIRED }
}
