package ai.intentoms.statemachine.model;

import java.util.List;
import java.util.Map;

/**
 * Per-entity result of moving in-flight entities between model versions (FR-SM-06). An entity is {@code migrated}
 * when every state currently holding quantity exists in the target version; otherwise it is {@code skipped} with the
 * unmapped state ids listed in {@code reason}.
 */
public record MigrationReport(String modelId, int fromVersion, int toVersion, boolean dryRun, int total, int migrated, int skipped, List<EntityMigration> perEntity) {
    public MigrationReport { perEntity = perEntity == null ? List.of() : List.copyOf(perEntity); }

    /** @param status {@code migrated} or {@code skipped}; {@code states} is the quantity map at migration time. */
    public record EntityMigration(String entityType, String entityId, String status, String reason, Map<String, Integer> states) {}
}
