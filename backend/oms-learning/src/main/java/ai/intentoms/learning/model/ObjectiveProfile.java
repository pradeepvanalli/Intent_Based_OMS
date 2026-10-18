package ai.intentoms.learning.model;

import java.time.Instant;
import java.util.Map;

/**
 * Tenant-weighted reward terms, versioned like any policy (requirements doc §7, table {@code objective_profile}).
 * XP-07: a profile starts as a DRAFT (typed weights, or drafted from plain English: {@code sourceText}, {@code
 * draftMethod} "llm" or "template", and a per-term {@code rationale}) and becomes live only when a different person
 * approves it.
 */
public record ObjectiveProfile(int version, Status status, ObjectiveWeights weights, Map<String, Object> constraints,
                                String approvedBy, Instant approvedAt, String createdBy, Instant createdAt,
                                String sourceText, String draftMethod, Map<String, String> rationale) {
    public enum Status { DRAFT, APPROVED, RETIRED }

    public ObjectiveProfile(int version, Status status, ObjectiveWeights weights, Map<String, Object> constraints,
                            String approvedBy, Instant approvedAt, String createdBy, Instant createdAt) {
        this(version, status, weights, constraints, approvedBy, approvedAt, createdBy, createdAt, null, "manual", Map.of());
    }
}
