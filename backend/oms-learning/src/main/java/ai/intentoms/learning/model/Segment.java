package ai.intentoms.learning.model;

import java.time.Instant;
import java.util.Map;

/** An analyst-discovered, statistics-defined customer/behavioural segment (CI-02). */
public record Segment(String segmentId, int version, String label, String description, Map<String, Object> definition, int size, String createdBy, Instant createdAt) {}
